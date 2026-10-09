version = "1.0.2"
description = "Translate messages from the message menu, or automatically, into the language you choose."

aliucord {
    changelog.set("""
        # 1.0.2
        * Added a /translate command that translates a message and sends it.
        * Moved the Translate button to the bottom of the message menu.

        # 1.0.1
        * Added DeepL and LibreTranslate as translation services.
        * The language picker now shows each language's code.
        * The translated tag now shows which language a message was translated from and to.

        # 1.0.0
        * Initial release.
    """.trimIndent())
}
