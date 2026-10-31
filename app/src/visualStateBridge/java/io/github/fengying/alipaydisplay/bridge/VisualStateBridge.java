package io.github.fengying.alipaydisplay.bridge;

import java.util.function.LongConsumer;

/** 由宿主类加载器解析SDK父类，完成通知只经标准Java接口返回。 */
public final class VisualStateBridge extends com.alipay.mywebview.sdk.WebView.VisualStateCallback {
    private final LongConsumer completion;

    public VisualStateBridge(LongConsumer completion) {
        this.completion = completion;
    }

    @Override
    public void onComplete(long requestId) {
        completion.accept(requestId);
    }
}
