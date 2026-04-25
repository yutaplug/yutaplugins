package xyz.wingio.plugins.markdownfix;

import android.content.Context;

import com.discord.simpleast.core.parser.ParseSpec;
import com.discord.simpleast.core.parser.Parser;
import com.discord.simpleast.core.parser.Rule;
import com.discord.utilities.textprocessing.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BulletPointRule extends Rule.BlockRule<MessageRenderContext, BulletPointNode<MessageRenderContext>, MessageParseState> {
    final private Context context;

    public BulletPointRule(Context context) {
        super(Pattern.compile("^\\s*([*-])\\s+(.+)(?=\\n|$)"));
        this.context = context;
    }

    @Override
    public ParseSpec<MessageRenderContext, MessageParseState> parse(Matcher matcher, Parser<MessageRenderContext, ? super BulletPointNode<MessageRenderContext>, MessageParseState> parser, MessageParseState s) {
        return new ParseSpec<>(new BulletPointNode(context), s, matcher.start(2), matcher.end(2));
    }
}