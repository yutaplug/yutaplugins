package xyz.wingio.plugins.markdownfix;

import com.aliucord.utils.DimenUtils;
import com.discord.simpleast.core.node.Node;
import com.discord.utilities.color.ColorCompat;

import android.content.Context;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.style.*;

import com.lytefast.flexinput.R;

public class BulletPointNode<MessageRenderContext> extends Node.a<MessageRenderContext> {
  final private Context context;

  public BulletPointNode(Context context){
    super();
    this.context = context;
  }

  @Override
  public void render(SpannableStringBuilder builder, MessageRenderContext renderContext) {
    int length = builder.length();
    super.render(builder, renderContext);

    int greyColor = ColorCompat.getThemedColor(context, R.b.colorTextMuted);
    BulletSpan span = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? new BulletSpan(/* gapWidth = */ DimenUtils.dpToPx(8), greyColor, /* bulletRadius = */ 6) : new BulletSpan(/* gapWidth = */ DimenUtils.dpToPx(8), greyColor);

    builder.setSpan(span, length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new StyleSpan(android.graphics.Typeface.NORMAL), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
}