package com.xingyao.card.core.bootstrap;

/**
 * Activity → Service 的动作事件，通过 EventBus 发送。
 * 例如：管理员输入激活码后通知 Service 继续启动流程。
 *
 * <pre>{@code
 * // Activity 中输入激活码后
 * EventBus.getDefault().post(
 *     new BootstrapActionEvent(BootstrapActionEvent.Action.PROVIDE_ACTIVATION_CODE, "ABC-123"));
 * }</pre>
 */
public class BootstrapActionEvent {

    public enum Action {
        /** 提供激活码（路径B） */
        PROVIDE_ACTIVATION_CODE,
        /** 重试启动流程 */
        RETRY,
        /** 跳过激活（仅测试/被授权场景） */
        SKIP_ACTIVATION,
    }

    public final Action action;
    public final String code; // activate code for PROVIDE_ACTIVATION_CODE

    public BootstrapActionEvent(Action action) {
        this(action, null);
    }

    public BootstrapActionEvent(Action action, String code) {
        this.action = action;
        this.code = code;
    }

    @Override
    public String toString() {
        return "BootstrapActionEvent{" + action + "}";
    }
}
