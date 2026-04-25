package xyz.wingio.plugins.markdownfix;

import android.content.Context;

import com.discord.simpleast.core.parser.ParseSpec;
import com.discord.simpleast.core.parser.Parser;
import com.discord.simpleast.core.parser.Rule;
import com.discord.utilities.textprocessing.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class SubtextRule extends Rule.BlockRule<MessageRenderContext, SubtextNode<MessageRenderContext>, MessageParseState> {
    private final Context context;

    public SubtextRule(Context context) {
        super(Pattern.compile("^[ \\t]*(-#)[ \\t]+(.+?)(?=\\n|$)"));
        this.context = context;
    }

    @Override
    public ParseSpec<MessageRenderContext, MessageParseState> parse(Matcher matcher, Parser<MessageRenderContext, ? super SubtextNode<MessageRenderContext>, MessageParseState> parser, MessageParseState s) {
        return new ParseSpec<>(new SubtextNode(context), s, matcher.start(2), matcher.end(2));
    }
}
