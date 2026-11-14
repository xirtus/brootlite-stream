package app.brootlite.tv.kodi;

import app.brootlite.tv.kodi.IPyDialogCallback;

interface IPyAddonService {
    String executePluginUrl(String pluginUrl, String kodiEnvDir, IPyDialogCallback callback);
    String listInstalledVideoAddons(String kodiEnvDir);
    oneway void killSelfNow();
}
