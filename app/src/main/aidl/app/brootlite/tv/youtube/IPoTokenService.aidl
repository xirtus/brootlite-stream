package app.brootlite.tv.youtube;

interface IPoTokenService {
    /**
     * Mints a YouTube BotGuard Proof-of-Origin token (poToken) inside the isolated :potoken process.
     * Returns null if BotGuard times out or WebView is unavailable on this firmware.
     */
    String mintPoToken(String identifier, String visitorData, boolean isTvClient);

    /**
     * Immediately terminates the :potoken process (Process.killProcess) so 0.0 MB WebView RAM remains.
     */
    oneway void killSelf();
}
