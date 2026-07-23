package com.miniclaudecode.browser;

/**
 * 建立或切换浏览器会话的抽象边界
 */
public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
