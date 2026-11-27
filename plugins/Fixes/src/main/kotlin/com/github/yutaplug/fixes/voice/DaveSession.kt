package com.github.yutaplug.fixes.voice

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.mls.TreeKEM.LeafIndex
import org.bouncycastle.mls.TreeKEM.LeafNode
import org.bouncycastle.mls.TreeKEM.LifeTime
import org.bouncycastle.mls.codec.Capabilities
import org.bouncycastle.mls.codec.ContentType
import org.bouncycastle.mls.codec.Credential
import org.bouncycastle.mls.codec.CredentialType
import org.bouncycastle.mls.codec.Extension
import org.bouncycastle.mls.codec.ExtensionType
import org.bouncycastle.mls.codec.ExternalSender
import org.bouncycastle.mls.codec.KeyPackage
import org.bouncycastle.mls.codec.MLSInputStream
import org.bouncycastle.mls.codec.MLSMessage
import org.bouncycastle.mls.codec.MLSOutputStream
import org.bouncycastle.mls.codec.Welcome
import org.bouncycastle.mls.codec.WireFormat
import org.bouncycastle.mls.crypto.MlsCipherSuite
import org.bouncycastle.mls.crypto.Secret
import org.bouncycastle.mls.protocol.Group
import java.security.SecureRandom

/**
 * DAVE's MLS group key exchange for one voice connection (DAVE protocol v1.1, mirroring libdave's Session), with
 * the voice gateway as delivery service and external sender. Not thread safe: the gateway calls it from one thread.
 */
internal class DaveSession(
    private val selfUserId: Long,
    private val groupId: Long,
    private val keys: MediaKeys,
    private val out: Output,
    private val log: (String) -> Unit,
) {
    interface Output {
        fun sendJson(op: Int, data: org.json.JSONObject)
        fun sendBinary(op: Int, payload: ByteArray)
    }

    private val suite = KeyRatchet.SUITE
    private val random = SecureRandom()

    /** DAVE protocol version of the session; 0 = transport encryption only. */
    var protocolVersion = 0
        private set

    private var externalSenderBytes: ByteArray? = null
    private var externalSender: ExternalSender? = null

    // Our identity: one ephemeral signature key for the whole voice connection.
    private val signatureKeys: AsymmetricCipherKeyPair = suite.generateSignatureKeyPair()
    private val sigPriv: ByteArray = suite.serializeSignaturePrivateKey(signatureKeys.private)

    private var hpkeLeafKeys: AsymmetricCipherKeyPair? = null
    private var leafNode: LeafNode? = null
    private var joinInitKeys: AsymmetricCipherKeyPair? = null
    private var joinKeyPackage: KeyPackage? = null

    /** Our own group (just us) while there's no established group yet. */
    private var pendingGroup: Group? = null

    /** The established group (epoch > 0). */
    private var currentGroup: Group? = null

    /** Commits we produced this epoch with the group each leads to; the gateway may pick any of them. */
    private val outboundCommits = ArrayList<Pair<ByteArray, Group>>()

    /** Prepared transition id -> protocol version. */
    private val transitions = HashMap<Int, Int>()

    val recognizedUsers = HashSet<Long>()

    var epoch = 0L
        private set
    var privacyCode: String? = null
        private set
    var lastError: String? = null
        private set

    val established get() = currentGroup != null

    // region gateway events

    /** select_protocol_ack (4) */
    fun onSelectProtocolAck(version: Int) {
        log("DAVE protocol version $version")
        if (version > 0) {
            init(version)
            sendKeyPackage()
        } else {
            protocolVersion = 0
            prepareRatchets(INIT_TRANSITION, 0)
        }
    }

    /** dave_mls_external_sender_package (25) */
    fun onExternalSender(payload: ByteArray) {
        if (currentGroup != null) {
            log("external sender after the group was established, ignored")
            return
        }
        val sender = MLSInputStream.decode(payload, ExternalSender::class.java) as ExternalSender
        if (pendingGroup != null && externalSenderBytes?.contentEquals(payload) == true) return
        externalSenderBytes = payload
        externalSender = sender
        if (leafNode != null) createPendingGroup()
    }

    /** dave_protocol_prepare_epoch (24) */
    fun onPrepareEpoch(epoch: Long, version: Int) {
        log("prepare epoch $epoch (version $version)")
        if (epoch == 1L) {
            init(version)
            sendKeyPackage()
        }
    }

    /** dave_protocol_prepare_transition (21) */
    fun onPrepareTransition(transitionId: Int, version: Int) {
        log("prepare transition $transitionId (version $version)")
        prepareRatchets(transitionId, version)
        if (transitionId != INIT_TRANSITION) sendReady(transitionId)
    }

    /** dave_protocol_execute_transition (22) */
    fun onExecuteTransition(transitionId: Int) {
        val version = transitions.remove(transitionId) ?: run {
            log("execute transition $transitionId: not prepared")
            return
        }
        log("execute transition $transitionId (version $version)")
        executeTransition(version)
    }

    /** dave_mls_proposals (27) */
    fun onProposals(payload: ByteArray) {
        val base = pendingGroup ?: currentGroup ?: run {
            log("proposals without a group, ignored")
            return
        }
        val revoke = payload[0].toInt() != 0
        val vector = payload.copyOfRange(1, payload.size)
        if (revoke) {
            val refs = readOpaqueVector(vector)
            val pending = BcmlsAccess.pendingProposals(base)
            val it = pending.iterator()
            var removed = 0
            while (it.hasNext()) {
                val ref = BcmlsAccess.proposalRef(it.next())
                if (refs.any { r -> r.contentEquals(ref) }) {
                    it.remove()
                    removed++
                }
            }
            log("revoked $removed of ${refs.size} proposals")
        } else {
            val messages = ArrayList<MLSMessage>()
            MLSInputStream(vector).readList(messages, MLSMessage::class.java)
            for (m in messages) {
                if (m.wireFormat != WireFormat.mls_public_message || m.contentType != ContentType.PROPOSAL) {
                    throw IllegalArgumentException("unexpected proposal message")
                }
                base.handle(MLSOutputStream.encode(m), null)
            }
            log("queued ${messages.size} proposals")
        }

        if (BcmlsAccess.pendingProposals(base).isEmpty()) return

        val result = base.commit(
            Secret(randomBytes(32)),
            Group.CommitOptions(emptyList(), true, false, Group.LeafNodeOptions()),
            Group.MessageOptions(false, ByteArray(0), 0),
            Group.CommitParameters(Group.NORMAL_COMMIT_PARAMS),
        )
        val commit = MLSOutputStream.encode(result.message)
        if (outboundCommits.size >= MAX_OUTBOUND_COMMITS) outboundCommits.removeAt(0)
        outboundCommits.add(commit to result.group)
        val welcome = result.message.welcome
        val payloadOut = if (welcome != null && BcmlsAccess.welcomeSecrets(welcome) > 0) {
            Bytes.concat(commit, MLSOutputStream.encode(welcome))
        } else {
            commit
        }
        log("sending commit (${payloadOut.size} bytes)")
        out.sendBinary(OP_MLS_COMMIT_WELCOME, payloadOut)
    }

    /** dave_mls_announce_commit_transition (29) */
    fun onAnnounceCommit(transitionId: Int, commit: ByteArray) {
        val base = pendingGroup ?: currentGroup ?: run {
            log("commit without a group, ignored")
            return
        }
        val ours = outboundCommits.firstOrNull { it.first.contentEquals(commit) }
        if (currentGroup == null && ours == null) {
            // Not in the group yet and it's someone else's initial commit: our welcome follows.
            log("commit for a group we're not in yet, waiting for the welcome")
            return
        }
        try {
            val next = base.handle(commit, ours?.second) ?: throw IllegalStateException("commit produced no group")
            replaceGroup(next)
            log("commit applied, epoch $epoch")
            prepareRatchets(transitionId, protocolVersion)
            if (transitionId != INIT_TRANSITION) sendReady(transitionId)
        } catch (e: Throwable) {
            fail("commit failed: $e")
            sendInvalid(transitionId)
            init(protocolVersion)
            sendKeyPackage()
        }
    }

    /** dave_mls_welcome (30) */
    fun onWelcome(transitionId: Int, welcomeBytes: ByteArray) {
        if (currentGroup != null) {
            log("welcome while already in a group, ignored")
            return
        }
        try {
            val init = joinInitKeys ?: throw IllegalStateException("no key package")
            val kp = joinKeyPackage ?: throw IllegalStateException("no key package")
            val leafKeys = hpkeLeafKeys ?: throw IllegalStateException("no leaf keys")
            val welcome = MLSInputStream.decode(welcomeBytes, Welcome::class.java) as Welcome
            val group = Group(
                suite.hpke.serializePrivateKey(init.private),
                leafKeys,
                sigPriv,
                kp,
                welcome,
                null,
                HashMap(),
                HashMap(),
            )
            verifyGroup(group)
            replaceGroup(group)
            log("welcomed into the group, epoch $epoch")
            prepareRatchets(transitionId, protocolVersion)
            if (transitionId != INIT_TRANSITION) sendReady(transitionId)
        } catch (e: Throwable) {
            fail("welcome failed: $e")
            sendInvalid(transitionId)
            sendKeyPackage()
        }
    }

    fun onClientsConnect(userIds: List<Long>) {
        recognizedUsers.addAll(userIds)
    }

    fun onClientDisconnect(userId: Long) {
        recognizedUsers.remove(userId)
    }

    // endregion

    private fun init(version: Int) {
        protocolVersion = version
        currentGroup = null
        pendingGroup = null
        outboundCommits.clear()
        joinKeyPackage = null
        joinInitKeys = null
        epoch = 0
        privacyCode = null

        val leafKeys = suite.hpke.generatePrivateKey()
        hpkeLeafKeys = leafKeys
        val caps = Capabilities()
        BcmlsAccess.restrictCapabilities(caps, MlsCipherSuite.MLS_128_DHKEMP256_AES128GCM_SHA256_P256)
        leafNode = LeafNode(
            suite,
            suite.hpke.serializePublicKey(leafKeys.public),
            suite.serializeSignaturePublicKey(signatureKeys.public),
            Credential.forBasic(Bytes.beU64(selfUserId)),
            caps,
            LifeTime(),
            emptyList(),
            sigPriv,
        )
        if (externalSender != null) createPendingGroup()
    }

    private fun createPendingGroup() {
        val leaf = leafNode ?: return
        val sender = externalSender ?: return
        val leafKeys = hpkeLeafKeys ?: return
        outboundCommits.clear()
        pendingGroup = Group(
            Bytes.beU64(groupId),
            suite,
            leafKeys,
            sigPriv,
            leaf,
            listOf(Extension.externalSender(listOf(sender))),
        )
        log("created pending group")
    }

    private fun sendKeyPackage() {
        val leaf = leafNode ?: return
        val init = suite.hpke.generatePrivateKey()
        val kp = KeyPackage(suite, suite.hpke.serializePublicKey(init.public), leaf, emptyList(), sigPriv)
        joinInitKeys = init
        joinKeyPackage = kp
        log("sending key package")
        out.sendBinary(OP_MLS_KEY_PACKAGE, MLSOutputStream.encode(kp))
    }

    private fun sendReady(transitionId: Int) {
        out.sendJson(OP_READY_FOR_TRANSITION, org.json.JSONObject().put("transition_id", transitionId))
    }

    private fun sendInvalid(transitionId: Int) {
        out.sendJson(OP_MLS_INVALID_COMMIT_WELCOME, org.json.JSONObject().put("transition_id", transitionId))
    }

    private fun replaceGroup(group: Group) {
        currentGroup = group
        pendingGroup = null
        outboundCommits.clear()
        epoch = group.epoch
        privacyCode = try {
            displayableCode(group.epochAuthenticator, 30, 5)
        } catch (e: Throwable) {
            null
        }
        lastError = null
    }

    /** Welcomed groups must carry exactly the external sender the gateway gave us, and no duplicate users. */
    private fun verifyGroup(group: Group) {
        val expected = externalSenderBytes ?: throw IllegalStateException("no external sender")
        val senders = group.extensions.filter { it.extensionType == ExtensionType.EXTERNAL_SENDERS }
        if (senders.size != 1) throw IllegalArgumentException("group has ${senders.size} external senders extensions")
        val list = senders[0].senders
        if (list.size != 1 || !MLSOutputStream.encode(list[0]).contentEquals(expected)) {
            throw IllegalArgumentException("group has an unexpected external sender")
        }
        val seen = HashSet<Long>()
        for (uid in rosterOf(group)) if (!seen.add(uid)) throw IllegalArgumentException("duplicate user $uid in group")
    }

    private fun rosterOf(group: Group): List<Long> {
        val users = ArrayList<Long>()
        val tree = group.tree
        val size = tree.size.leafCount().toInt()
        for (i in 0 until size) {
            val leaf = tree.getLeafNode(LeafIndex(i)) ?: continue
            val cred = leaf.credential ?: continue
            if (cred.credentialType != CredentialType.basic) continue
            users.add(Bytes.fromBeU64(cred.identity))
        }
        return users
    }

    private fun ratchetFor(userId: Long): KeyRatchet? {
        val group = currentGroup ?: return null
        val base = group.keySchedule.MLSExporter(EXPORT_LABEL, Bytes.leU64(userId), KeyRatchet.KEY_BYTES)
        return KeyRatchet(base)
    }

    private fun prepareRatchets(transitionId: Int, version: Int) {
        val users = HashSet<Long>()
        currentGroup?.let { users.addAll(rosterOf(it)) }
        users.addAll(recognizedUsers)
        users.addAll(keys.knownUsers())
        users.remove(selfUserId)
        for (u in users) {
            val d = keys.decryptor(u)
            if (version > 0) d.transitionToRatchet(ratchetFor(u))
            d.transitionToPassthrough(version == 0)
        }
        keys.passthroughDefault = version == 0
        if (transitionId == INIT_TRANSITION) {
            executeTransition(version)
        } else {
            transitions[transitionId] = version
        }
    }

    private fun executeTransition(version: Int) {
        if (version == 0) {
            currentGroup = null
            pendingGroup = null
            privacyCode = null
        }
        protocolVersion = version
        keys.encryptor.setRatchet(if (version > 0) ratchetFor(selfUserId) else null)
        keys.encryptor.passthrough = version == 0
    }

    private fun fail(msg: String) {
        lastError = msg
        log(msg)
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    /** opaque refs<V>: a vector of opaque<V>. */
    private fun readOpaqueVector(b: ByteArray): List<ByteArray> {
        val pos = intArrayOf(0)
        val total = Bytes.readMlsVarint(b, pos)
        val end = pos[0] + total
        val out = ArrayList<ByteArray>()
        while (pos[0] < end) {
            val len = Bytes.readMlsVarint(b, pos)
            out.add(b.copyOfRange(pos[0], pos[0] + len))
            pos[0] += len
        }
        return out
    }

    companion object {
        const val INIT_TRANSITION = 0
        const val EXPORT_LABEL = "Discord Secure Frames v0"
        const val MAX_OUTBOUND_COMMITS = 8

        const val OP_PREPARE_TRANSITION = 21
        const val OP_EXECUTE_TRANSITION = 22
        const val OP_READY_FOR_TRANSITION = 23
        const val OP_PREPARE_EPOCH = 24
        const val OP_MLS_EXTERNAL_SENDER = 25
        const val OP_MLS_KEY_PACKAGE = 26
        const val OP_MLS_PROPOSALS = 27
        const val OP_MLS_COMMIT_WELCOME = 28
        const val OP_MLS_ANNOUNCE_COMMIT = 29
        const val OP_MLS_WELCOME = 30
        const val OP_MLS_INVALID_COMMIT_WELCOME = 31

        /** DAVE "displayable code": groups of [group] digits from big-endian chunks of [data]. */
        fun displayableCode(data: ByteArray, length: Int, group: Int): String {
            val sb = StringBuilder()
            var mod = 1L
            for (k in 0 until group) mod *= 10
            var i = 0
            while (i < length) {
                var v = 0L
                for (j in 0 until group) v = (v shl 8) or (data[i + j].toLong() and 0xFF)
                val s = (v % mod).toString()
                for (k in s.length until group) sb.append('0')
                sb.append(s)
                if (i + group < length) sb.append(' ')
                i += group
            }
            return sb.toString()
        }
    }
}

/** bcmls internals the protocol needs that it doesn't expose. */
internal object BcmlsAccess {
    private fun field(c: Class<*>, name: String) = c.getDeclaredField(name).apply { isAccessible = true }

    private val groupPending by lazy { field(Group::class.java, "pendingProposals") }
    private val cachedRef by lazy { field(Class.forName("org.bouncycastle.mls.protocol.CachedProposal"), "proposalRef") }
    private val welcomeSecrets by lazy { field(Welcome::class.java, "secrets") }
    private val capSuites by lazy { field(Capabilities::class.java, "cipherSuites") }
    private val capCredentials by lazy { field(Capabilities::class.java, "credentials") }

    @Suppress("UNCHECKED_CAST")
    fun pendingProposals(g: Group) = groupPending.get(g) as MutableList<Any>

    fun proposalRef(cached: Any) = cachedRef.get(cached) as ByteArray

    fun welcomeSecrets(w: Welcome) = (welcomeSecrets.get(w) as List<*>?)?.size ?: 0

    /** Like libdave: only our ciphersuite and basic credentials. */
    fun restrictCapabilities(c: Capabilities, suite: Short) {
        try {
            capSuites.set(c, arrayListOf(suite))
            capCredentials.set(c, arrayListOf(1.toShort())) // basic
        } catch (e: Throwable) {
            // keep bcmls' defaults, which include these
        }
    }
}
