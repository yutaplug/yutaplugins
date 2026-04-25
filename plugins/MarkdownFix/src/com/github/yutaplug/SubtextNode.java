package xyz.wingio.plugins.markdownfix;

import com.discord.simpleast.core.node.Node;
import com.discord.utilities.color.ColorCompat;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.style.*;

import com.lytefast.flexinput.R;

public class SubtextNode<MessageRenderContext> extends Node.a<MessageRenderContext> {
  private final Context context;

  public SubtextNode(Context context){
    super();
    this.context = context;
  }

  @Override
  public void render(SpannableStringBuilder builder, MessageRenderContext renderContext) {
    int length = builder.length();
    super.render(builder, renderContext);

    builder.setSpan(new RelativeSizeSpan(0.85f), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);

    int greyColor = ColorCompat.getThemedColor(context, R.b.colorTextMuted);

    // Check for existing ClickableSpans (links) and only apply grey color to non-link text
    // Detect links more robustly by considering both ClickableSpan and URLSpan
    ClickableSpan[] clickableSpans = builder.getSpans(length, builder.length(), ClickableSpan.class);
    URLSpan[] urlSpans = builder.getSpans(length, builder.length(), URLSpan.class);

    java.util.List<int[]> linkRanges = new java.util.ArrayList<>();
    // Collect ranges from ClickableSpan-derived links
    for (ClickableSpan span : clickableSpans) {
      int spanStart = builder.getSpanStart(span);
      int spanEnd = builder.getSpanEnd(span);
      if (spanStart >= length && spanEnd <= builder.length()) {
        linkRanges.add(new int[]{spanStart, spanEnd});
      }
    }
    // Collect ranges from URLSpan-based links (in case some links use a different span type)
    for (URLSpan span : urlSpans) {
      int spanStart = builder.getSpanStart(span);
      int spanEnd = builder.getSpanEnd(span);
      if (spanStart >= length && spanEnd <= builder.length()) {
        linkRanges.add(new int[]{spanStart, spanEnd});
      }
    }

    // If no link ranges were found, color the entire subtext grey
    if (linkRanges.isEmpty()) {
      builder.setSpan(new ForegroundColorSpan(greyColor), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    } else {
      // Sort and merge overlapping link ranges to simplify coloring of non-link text
      linkRanges.sort((a, b) -> Integer.compare(a[0], b[0]));
      java.util.List<int[]> merged = new java.util.ArrayList<>();
      int curStart = linkRanges.get(0)[0];
      int curEnd = linkRanges.get(0)[1];
      for (int i = 1; i < linkRanges.size(); i++) {
        int[] r = linkRanges.get(i);
        if (r[0] <= curEnd) {
          curEnd = Math.max(curEnd, r[1]);
        } else {
          merged.add(new int[]{curStart, curEnd});
          curStart = r[0];
          curEnd = r[1];
        }
      }
      merged.add(new int[]{curStart, curEnd});

      // Apply grey to the non-link gaps between merged link ranges
      int start = length;
      int end = builder.length();
      int currentPos = start;
      for (int[] range : merged) {
        if (currentPos < range[0]) {
          builder.setSpan(new ForegroundColorSpan(greyColor), currentPos, range[0], SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        currentPos = Math.max(currentPos, range[1]);
      }
      if (currentPos < end) {
        builder.setSpan(new ForegroundColorSpan(greyColor), currentPos, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
  }
}
