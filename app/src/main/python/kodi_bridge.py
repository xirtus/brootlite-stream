# -*- coding: utf-8 -*-
"""
BrootLite Kodi Python Runtime Bridge (Kodi 21 Omega compatible).
Runs unmodified Kodi video plugins (standard Kodi video plugins,
script.module.resolveurl, script.module.resolveurl.xxx, etc.) inside the isolated
:pyaddon process and returns JSON directory listings or resolved stream URLs.
"""

import hashlib
import json
import os
import re
import runpy
import shutil
import sqlite3
import sys
import time
import traceback
import types
import urllib.parse
import xml.etree.ElementTree as ET


class _BridgeState:
    def __init__(self):
        self.kodi_env = ""
        self.addon_id = ""
        self.addon_dir = ""
        self.last_addon_id = ""
        self.handle = 1
        self.callback = None
        self.items = []
        self.resolved_item = None
        self.redirect_url = None
        self.refresh = False
        self.content_type = "videos"
        self.category = ""
        self.window_props = {}
        self.po_cache = {}
        self.addon_xml_cache = {}
        self.sys_paths_initialized = False

    def reset_request(self, kodi_env, addon_id, addon_dir, callback):
        self.kodi_env = kodi_env.rstrip("/")
        self.addon_id = addon_id
        self.addon_dir = addon_dir.rstrip("/")
        self.callback = callback
        self.items = []
        self.resolved_item = None
        self.redirect_url = None
        self.refresh = False
        self.content_type = "videos"
        self.category = ""


_STATE = _BridgeState()


def _translate_path(path):
    if not path:
        return ""
    p = str(path)
    env = _STATE.kodi_env
    mappings = (
        ("special://home/", env + "/"),
        ("special://home", env),
        ("special://xbmc/", env + "/"),
        ("special://xbmc", env),
        ("special://masterprofile/", env + "/userdata/"),
        ("special://masterprofile", env + "/userdata"),
        ("special://profile/", env + "/userdata/"),
        ("special://profile", env + "/userdata"),
        ("special://userdata/", env + "/userdata/"),
        ("special://userdata", env + "/userdata"),
        ("special://temp/", env + "/temp/"),
        ("special://temp", env + "/temp"),
        ("special://logpath/", env + "/temp/"),
        ("special://logpath", env + "/temp"),
        ("special://database/", env + "/userdata/Database/"),
        ("special://database", env + "/userdata/Database"),
        ("special://thumbnails/", env + "/userdata/Thumbnails/"),
        ("special://thumbnails", env + "/userdata/Thumbnails"),
    )
    low = p.lower()
    for prefix, target in mappings:
        if low.startswith(prefix):
            rest = p[len(prefix):]
            return os.path.normpath(os.path.join(target, rest)) + ("/" if p.endswith("/") and not rest.endswith("/") else "")
    return p


def _strip_bbcode(text):
    if not text:
        return ""
    s = str(text)
    s = re.sub(r"\[COLOR[^\]]*\]", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\[/COLOR\]", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\[/?(?:B|I|LIGHT|UPPERCASE|LOWERCASE|CAPITALIZE)\]", "", s, flags=re.IGNORECASE)
    s = s.replace("[CR]", "\n")
    return s.strip()


def _load_po_strings(addon_dir):
    if addon_dir in _STATE.po_cache:
        return _STATE.po_cache[addon_dir]
    mapping = {}
    candidates = [
        os.path.join(addon_dir, "resources", "language", "resource.language.en_gb", "strings.po"),
        os.path.join(addon_dir, "resources", "language", "English", "strings.po"),
    ]
    lang_root = os.path.join(addon_dir, "resources", "language")
    if os.path.isdir(lang_root):
        for entry in sorted(os.listdir(lang_root)):
            if "en" in entry.lower():
                p = os.path.join(lang_root, entry, "strings.po")
                if p not in candidates:
                    candidates.append(p)
    for po_path in candidates:
        if os.path.isfile(po_path):
            try:
                with open(po_path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                blocks = re.split(r"\n\s*\n", content)
                for block in blocks:
                    m_ctx = re.search(r'msgctxt\s+"#(\d+)"', block)
                    if not m_ctx:
                        continue
                    code = int(m_ctx.group(1))
                    m_id = re.search(r'msgid\s+"((?:[^"\\]|\\.)*)"', block)
                    m_str = re.search(r'msgstr\s+"((?:[^"\\]|\\.)*)"', block)
                    val = ""
                    if m_str and m_str.group(1):
                        val = m_str.group(1)
                    elif m_id:
                        val = m_id.group(1)
                    val = val.replace('\\"', '"').replace("\\n", "\n")
                    mapping[code] = val
                if mapping:
                    break
            except Exception:
                pass
    # Also check legacy strings.xml
    if not mapping:
        xml_candidates = [
            os.path.join(addon_dir, "resources", "language", "English", "strings.xml"),
            os.path.join(addon_dir, "resources", "language", "resource.language.en_gb", "strings.xml"),
        ]
        for xp in xml_candidates:
            if os.path.isfile(xp):
                try:
                    tree = ET.parse(xp)
                    for s_el in tree.getroot().findall(".//string"):
                        sid = s_el.get("id")
                        if sid and sid.isdigit():
                            mapping[int(sid)] = s_el.text or ""
                    if mapping:
                        break
                except Exception:
                    pass
    _STATE.po_cache[addon_dir] = mapping
    return mapping


def _parse_addon_meta(addon_id):
    if addon_id == "xbmc.addon":
        return {"id": "xbmc.addon", "name": "Kodi", "version": "21.2.0", "path": _STATE.kodi_env, "icon": "", "fanart": ""}
    if addon_id == "xbmc.python":
        return {"id": "xbmc.python", "name": "Python", "version": "3.0.1", "path": _STATE.kodi_env, "icon": "", "fanart": ""}
    if addon_id in _STATE.addon_xml_cache:
        return _STATE.addon_xml_cache[addon_id]

    addon_dir = os.path.join(_STATE.kodi_env, "addons", addon_id)
    xml_path = os.path.join(addon_dir, "addon.xml")
    meta = {
        "id": addon_id,
        "name": addon_id,
        "version": "1.0.0",
        "author": "",
        "path": addon_dir,
        "icon": os.path.join(addon_dir, "icon.png"),
        "fanart": os.path.join(addon_dir, "fanart.jpg"),
    }
    if os.path.isfile(xml_path):
        try:
            tree = ET.parse(xml_path)
            root = tree.getroot()
            meta["id"] = root.get("id", addon_id)
            meta["name"] = root.get("name", addon_id)
            meta["version"] = root.get("version", "1.0.0")
            meta["author"] = root.get("provider-name", "")
            icon_el = root.find(".//assets/icon")
            if icon_el is not None and icon_el.text:
                meta["icon"] = os.path.join(addon_dir, icon_el.text.strip())
            fanart_el = root.find(".//assets/fanart")
            if fanart_el is not None and fanart_el.text:
                meta["fanart"] = os.path.join(addon_dir, fanart_el.text.strip())
        except Exception:
            pass
    _STATE.addon_xml_cache[addon_id] = meta
    return meta


class _KodiAddon:
    def __init__(self, id=None):
        if not id:
            # Infer addon ID if called from inside script.module.*
            inferred = None
            try:
                frame = sys._getframe(1)
                caller_file = os.path.abspath(frame.f_code.co_filename)
                addons_root = os.path.abspath(os.path.join(_STATE.kodi_env, "addons")) + os.sep
                if caller_file.startswith(addons_root):
                    rel = caller_file[len(addons_root):]
                    cand = rel.split(os.sep, 1)[0]
                    if cand.startswith("script.module."):
                        inferred = cand
            except Exception:
                pass
            self._id = inferred or _STATE.addon_id or "plugin.video.default"
        else:
            self._id = str(id)

        self._meta = _parse_addon_meta(self._id)
        self._profile_dir = os.path.join(_STATE.kodi_env, "userdata", "addon_data", self._id)
        self._settings = None

    def _ensure_settings(self):
        if self._settings is not None:
            return
        settings = {}
        # 1. Defaults from addon resources/settings.xml
        res_settings = os.path.join(self._meta["path"], "resources", "settings.xml")
        if os.path.isfile(res_settings):
            try:
                root = ET.parse(res_settings).getroot()
                for s_el in root.findall(".//setting"):
                    sid = s_el.get("id")
                    if not sid:
                        continue
                    def_child = s_el.find("default")
                    if def_child is not None:
                        settings[sid] = def_child.text or ""
                    elif "default" in s_el.attrib:
                        settings[sid] = s_el.get("default") or ""
                    else:
                        settings[sid] = ""
            except Exception:
                pass

        if self._id == "plugin.video.default" or not self._id.startswith("plugin.video."):
            settings.setdefault("age_verified", "true")
            settings.setdefault("cache_time", "4")
            settings.setdefault("content", "0")
            settings.setdefault("qualityask", "0")
            settings.setdefault("dontask", "false")
            settings.setdefault("universal_resolvers", "true")
            settings.setdefault("custom_sites", "true")
            settings.setdefault("m3u8_use_ia", "false")
            settings.setdefault("fs_enable", "false")
            settings.setdefault("enh_debug", "false")

        # 2. User overrides from userdata/addon_data/{id}/settings.xml
        user_settings = os.path.join(self._profile_dir, "settings.xml")
        if os.path.isfile(user_settings):
            try:
                root = ET.parse(user_settings).getroot()
                ver = root.get("version", "1")
                for s_el in root.findall(".//setting"):
                    sid = s_el.get("id")
                    if not sid:
                        continue
                    if ver == "2":
                        if s_el.text is not None:
                            settings[sid] = s_el.text
                        elif s_el.get("default") == "true":
                            settings.setdefault(sid, "")
                        else:
                            settings[sid] = ""
                    else:
                        settings[sid] = s_el.get("value", "")
            except Exception:
                pass

        # Generalize setting any key ending with "age" to true
        for k in list(settings.keys()):
            if k.endswith("age"):
                settings[k] = "true"
        settings["m3u8_use_ia"] = "false"

        self._settings = settings

    def getAddonInfo(self, info_id):
        key = str(info_id).lower()
        if key == "id":
            return self._meta["id"]
        if key == "name":
            return self._meta["name"]
        if key == "version":
            return self._meta["version"]
        if key == "author":
            return self._meta.get("author", "")
        if key == "path":
            return self._meta["path"] + "/"
        if key == "profile":
            os.makedirs(self._profile_dir, exist_ok=True)
            return self._profile_dir + "/"
        if key == "icon":
            return self._meta.get("icon", "")
        if key == "fanart":
            return self._meta.get("fanart", "")
        return ""

    def getSetting(self, setting_id):
        self._ensure_settings()
        return str(self._settings.get(str(setting_id), ""))

    def getSettingBool(self, setting_id):
        return self.getSetting(setting_id).lower() == "true"

    def getSettingInt(self, setting_id):
        try:
            return int(float(self.getSetting(setting_id) or "0"))
        except Exception:
            return 0

    def getSettingNumber(self, setting_id):
        try:
            return float(self.getSetting(setting_id) or "0")
        except Exception:
            return 0.0

    def getSettingString(self, setting_id):
        return self.getSetting(setting_id)

    def setSetting(self, id, value=""):
        self._ensure_settings()
        sid = str(id)
        sval = str(value)
        self._settings[sid] = sval
        self._persist_user_setting(sid, sval)

    def setSettingBool(self, id, value):
        self.setSetting(id, "true" if value else "false")

    def setSettingInt(self, id, value):
        self.setSetting(id, str(int(value)))

    def setSettingString(self, id, value):
        self.setSetting(id, str(value))

    def _persist_user_setting(self, sid, sval):
        try:
            os.makedirs(self._profile_dir, exist_ok=True)
            user_settings = os.path.join(self._profile_dir, "settings.xml")
            if os.path.isfile(user_settings):
                tree = ET.parse(user_settings)
                root = tree.getroot()
            else:
                root = ET.Element("settings", {"version": "2"})
                tree = ET.ElementTree(root)
            ver = root.get("version", "2")
            found = None
            for s_el in root.findall("setting"):
                if s_el.get("id") == sid:
                    found = s_el
                    break
            if found is None:
                found = ET.SubElement(root, "setting", {"id": sid})
            if ver == "2":
                if "default" in found.attrib:
                    del found.attrib["default"]
                found.text = sval
            else:
                found.set("value", sval)
            tree.write(user_settings, encoding="utf-8", xml_declaration=False)
        except Exception:
            pass

    def getLocalizedString(self, id):
        try:
            code = int(id)
        except Exception:
            return str(id)
        strings = _load_po_strings(self._meta["path"])
        if code in strings:
            return strings[code]
        # Also try active plugin's strings if called from module
        if self._meta["path"] != _STATE.addon_dir and _STATE.addon_dir:
            main_strings = _load_po_strings(_STATE.addon_dir)
            if code in main_strings:
                return main_strings[code]
        return ""

    def openSettings(self):
        if _STATE.callback:
            try:
                _STATE.callback.onNotification(self._meta["name"], "Addon settings are managed automatically by BrootLite.")
            except Exception:
                pass


class _VideoStreamDetail:
    def __init__(self, width=0, height=0, aspect=0.0, duration=0, codec="", stereoMode="", language="", hdrType=""):
        self.width = width or 0
        self.height = height or 0
        self.codec = codec or ""
        self.duration = duration or 0


class _InfoTagVideo:
    def __init__(self, owner):
        self._owner = owner
        self.title = ""
        self.plot = ""
        self.duration = 0
        self.media_type = "video"
        self.genres = []
        self.width = 0
        self.height = 0
        self.year = 0
        self.rating = 0.0

    def setTitle(self, title):
        self.title = str(title or "")

    def setPlot(self, plot):
        self.plot = str(plot or "")

    def setPlotOutline(self, plot):
        if not self.plot:
            self.plot = str(plot or "")

    def setDuration(self, secs):
        try:
            self.duration = int(secs or 0)
        except Exception:
            self.duration = 0

    def setMediaType(self, media_type):
        self.media_type = str(media_type or "video")

    def setGenres(self, genres):
        if isinstance(genres, (list, tuple)):
            self.genres = [str(g) for g in genres]

    def setYear(self, year):
        try:
            self.year = int(year or 0)
        except Exception:
            pass

    def setRating(self, rating, *args, **kwargs):
        try:
            self.rating = float(rating or 0.0)
        except Exception:
            pass

    def addVideoStream(self, stream_detail):
        if stream_detail is not None:
            self.width = getattr(stream_detail, "width", 0) or self.width
            self.height = getattr(stream_detail, "height", 0) or self.height

    def __getattr__(self, name):
        # Gracefully absorb any additional Kodi 20/21 InfoTagVideo setter calls
        if name.startswith("set") or name.startswith("add"):
            return lambda *a, **kw: None
        raise AttributeError(name)


class _ListItem:
    def __init__(self, label="", label2="", path="", offscreen=False, iconImage="", thumbnailImage=""):
        self._label = str(label or "")
        self._label2 = str(label2 or "")
        self._path = str(path or "")
        self._art = {}
        if iconImage:
            self._art["icon"] = str(iconImage)
        if thumbnailImage:
            self._art["thumb"] = str(thumbnailImage)
        self._properties = {}
        self._context_menu = []
        self._subtitles = []
        self._mime_type = ""
        self._video_tag = _InfoTagVideo(self)

    def getLabel(self):
        return self._label

    def setLabel(self, label):
        self._label = str(label or "")

    def getLabel2(self):
        return self._label2

    def setLabel2(self, label):
        self._label2 = str(label or "")

    def getPath(self):
        return self._path

    def setPath(self, path):
        self._path = str(path or "")

    def setArt(self, values):
        if isinstance(values, dict):
            for k, v in values.items():
                if v is not None:
                    self._art[str(k)] = str(v)

    def getArt(self, key):
        return self._art.get(str(key), "")

    def setInfo(self, type, infoLabels):
        if isinstance(infoLabels, dict):
            for k, v in infoLabels.items():
                kl = str(k).lower()
                if kl == "title" and v:
                    self._video_tag.setTitle(v)
                elif kl in ("plot", "plotoutline") and v:
                    self._video_tag.setPlot(v)
                elif kl == "duration" and v:
                    self._video_tag.setDuration(v)
                elif kl == "year" and v:
                    self._video_tag.setYear(v)

    def getVideoInfoTag(self):
        return self._video_tag

    def addStreamInfo(self, cType, dictionary):
        if str(cType).lower() == "video" and isinstance(dictionary, dict):
            self._video_tag.width = int(dictionary.get("width") or 0)
            self._video_tag.height = int(dictionary.get("height") or 0)

    def setProperty(self, key, value):
        self._properties[str(key)] = str(value)

    def setProperties(self, values):
        if isinstance(values, dict):
            for k, v in values.items():
                self._properties[str(k)] = str(v)

    def getProperty(self, key):
        return self._properties.get(str(key), "")

    def addContextMenuItems(self, items, replaceItems=False):
        if replaceItems:
            self._context_menu = []
        if isinstance(items, list):
            for item in items:
                if isinstance(item, (tuple, list)) and len(item) >= 2:
                    self._context_menu.append((str(item[0]), str(item[1])))

    def setSubtitles(self, subtitleFiles):
        if isinstance(subtitleFiles, (list, tuple)):
            self._subtitles = [str(s) for s in subtitleFiles if s]

    def setMimeType(self, mimetype):
        self._mime_type = str(mimetype or "")

    def setContentLookup(self, enable):
        pass

    def setIsFolder(self, isFolder):
        pass

    def to_dict(self, url="", is_folder=False):
        thumb = (
            self._art.get("poster")
            or self._art.get("thumb")
            or self._art.get("icon")
            or ""
        )
        if thumb in ("DefaultFolder.png", "DefaultVideo.png"):
            thumb = self._art.get("thumb") if self._art.get("thumb") not in ("DefaultFolder.png", "DefaultVideo.png") else ""
        if thumb and not thumb.startswith("http"):
            thumb = _translate_path(thumb)

        fanart = self._art.get("fanart") or ""
        if fanart and not fanart.startswith("http"):
            fanart = _translate_path(fanart)

        # Merge inputstream.adaptive headers back into resolved path if needed
        resolved_path = url or self._path
        ia_headers = (
            self._properties.get("inputstream.adaptive.stream_headers")
            or self._properties.get("inputstream.adaptive.common_headers")
            or self._properties.get("inputstream.adaptive.manifest_headers")
            or ""
        )
        if ia_headers and resolved_path and "|" not in resolved_path:
            resolved_path = resolved_path + "|" + ia_headers

        ctx_list = []
        for c_label, c_action in self._context_menu:
            # Extract plugin:// URL inside RunPlugin(...) or Container.Update(...)
            m = re.search(r"(?:RunPlugin|Container\.Update)\((.+)\)\s*$", c_action)
            plugin_target = m.group(1).strip("\"'") if m else c_action
            if plugin_target.startswith("plugin://"):
                ctx_list.append({"label": c_label, "cleanLabel": _strip_bbcode(c_label), "url": plugin_target})

        return {
            "label": self._label,
            "cleanLabel": _strip_bbcode(self._label),
            "url": resolved_path,
            "isFolder": bool(is_folder),
            "isPlayable": (not is_folder) or self._properties.get("IsPlayable", "").lower() == "true",
            "thumb": thumb or "",
            "fanart": fanart or "",
            "plot": self._video_tag.plot or "",
            "duration": self._video_tag.duration or 0,
            "width": self._video_tag.width or 0,
            "height": self._video_tag.height or 0,
            "mimeType": self._mime_type or "",
            "subtitles": self._subtitles,
            "contextMenu": ctx_list,
        }


class _Dialog:
    def notification(self, heading, message, icon="", time=3000, sound=True):
        if _STATE.callback:
            try:
                _STATE.callback.onNotification(_strip_bbcode(heading), _strip_bbcode(message))
            except Exception:
                pass

    def ok(self, heading, line1="", line2="", line3=""):
        msg = "\n".join(x for x in [line1, line2, line3] if x)
        if _STATE.callback:
            try:
                _STATE.callback.onNotification(_strip_bbcode(heading), _strip_bbcode(msg))
            except Exception:
                pass
        return True

    def yesno(self, heading, message="", nolabel="No", yeslabel="Yes", customlabel="", autoclose=0):
        # Auto-accept age verification prompts without interrupting navigation
        low_h = str(heading).lower()
        low_m = str(message).lower()
        if "age" in low_m or "adult" in low_m or "18" in low_m:
            return True
        if _STATE.callback:
            try:
                return bool(
                    _STATE.callback.onYesNo(
                        _strip_bbcode(heading),
                        _strip_bbcode(message),
                        _strip_bbcode(nolabel or "No"),
                        _strip_bbcode(yeslabel or "Yes"),
                    )
                )
            except Exception:
                return True
        return True

    def select(self, heading, list, autoclose=0, preselect=-1, useDetails=False):
        if not list:
            return -1
        options = []
        for entry in list:
            if isinstance(entry, _ListItem):
                options.append(_strip_bbcode(entry.getLabel()))
            else:
                options.append(_strip_bbcode(str(entry)))
        if len(options) == 1:
            return 0
        if _STATE.callback:
            try:
                return int(_STATE.callback.onSelect(_strip_bbcode(heading), options))
            except Exception:
                return 0
        return 0

    def input(self, heading, defaultt="", type=0, option=0, autoclose=0):
        if _STATE.callback:
            try:
                res = _STATE.callback.onInput(_strip_bbcode(heading), str(defaultt or ""), False)
                return str(res) if res is not None else ""
            except Exception:
                return ""
        return ""

    def textviewer(self, heading, text, usemono=False):
        if _STATE.callback:
            try:
                _STATE.callback.onTextBox(_strip_bbcode(heading), _strip_bbcode(text))
            except Exception:
                pass

    def browse(self, type, heading, shares="", mask="", useThumbs=False, treatAsFolder=False, defaultt="", enableMultiple=False):
        dl_dir = os.path.join(_STATE.kodi_env, "downloads")
        os.makedirs(dl_dir, exist_ok=True)
        return dl_dir + "/"

    def browseSingle(self, type, heading, shares="", mask="", useThumbs=False, treatAsFolder=False, defaultt=""):
        return self.browse(type, heading, shares, mask, useThumbs, treatAsFolder, defaultt)


class _DialogProgress:
    def __init__(self):
        self._canceled = False

    def create(self, heading, line1="", line2="", line3=""):
        self._canceled = False

    def update(self, percent, line1="", line2="", line3=""):
        pass

    def close(self):
        pass

    def iscanceled(self):
        return self._canceled

    def isFinished(self):
        return True


class _ControlStub:
    def __init__(self):
        self._label = ""
        self._text = ""

    def setLabel(self, label, *args, **kwargs):
        self._label = str(label or "")

    def setText(self, text):
        self._text = str(text or "")
        if _STATE.callback and self._text:
            try:
                _STATE.callback.onTextBox("Info", _strip_bbcode(self._text))
            except Exception:
                pass


class _Window:
    def __init__(self, existingWindowId=-1):
        self._id = existingWindowId
        self._controls = {}

    def getProperty(self, key):
        return _STATE.window_props.get(str(key), "")

    def setProperty(self, key, value):
        _STATE.window_props[str(key)] = str(value)

    def clearProperty(self, key):
        _STATE.window_props.pop(str(key), None)

    def clearProperties(self):
        _STATE.window_props.clear()

    def getFocusId(self):
        return 55

    def getControl(self, controlId):
        if controlId not in self._controls:
            self._controls[controlId] = _ControlStub()
        return self._controls[controlId]


class _Keyboard:
    def __init__(self, line="", heading="", hidden=False):
        self._default = str(line or "")
        self._heading = str(heading or "Enter text")
        self._hidden = bool(hidden)
        self._text = ""
        self._confirmed = False

    def setDefault(self, line):
        self._default = str(line or "")

    def setHeading(self, heading):
        self._heading = str(heading or "")

    def setHiddenInput(self, hidden):
        self._hidden = bool(hidden)

    def doModal(self, autoclose=0):
        if _STATE.callback:
            try:
                res = _STATE.callback.onInput(_strip_bbcode(self._heading), self._default, self._hidden)
                if res is not None and str(res) != "__CANCELLED__":
                    self._text = str(res)
                    self._confirmed = True
                else:
                    self._confirmed = False
            except Exception:
                self._confirmed = False
        else:
            self._confirmed = False

    def isConfirmed(self):
        return self._confirmed

    def getText(self):
        return self._text


class _Player:
    def __init__(self, *args, **kwargs):
        pass

    def play(self, item="", listitem=None, windowed=False, startpos=-1):
        url = str(item or "")
        if isinstance(listitem, _ListItem):
            if not url:
                url = listitem.getPath()
            _STATE.resolved_item = listitem.to_dict(url=url, is_folder=False)
        elif url:
            _STATE.resolved_item = {
                "label": "",
                "cleanLabel": "",
                "url": url,
                "isFolder": False,
                "isPlayable": True,
                "thumb": "",
                "fanart": "",
                "plot": "",
                "duration": 0,
                "width": 0,
                "height": 0,
                "mimeType": "",
                "subtitles": [],
                "contextMenu": [],
            }

    def isPlaying(self):
        return False

    def isPlayingVideo(self):
        return False

    def stop(self):
        pass


class _Monitor:
    def __init__(self, *args, **kwargs):
        pass

    def abortRequested(self):
        return False

    def waitForAbort(self, timeout=-1):
        return True


class _VfsFile:
    def __init__(self, filepath, mode="r"):
        real_path = _translate_path(filepath)
        parent = os.path.dirname(real_path)
        if "w" in mode and parent:
            os.makedirs(parent, exist_ok=True)
        bin_mode = "wb" if "w" in mode else "rb"
        self._f = open(real_path, bin_mode)

    def read(self, numBytes=0):
        raw = self._f.read(numBytes) if numBytes > 0 else self._f.read()
        try:
            return raw.decode("utf-8")
        except Exception:
            return raw.decode("latin-1", errors="ignore")

    def readBytes(self, numBytes=0):
        return bytearray(self._f.read(numBytes) if numBytes > 0 else self._f.read())

    def write(self, buffer):
        if isinstance(buffer, str):
            buffer = buffer.encode("utf-8")
        self._f.write(buffer)
        return True

    def size(self):
        pos = self._f.tell()
        self._f.seek(0, os.SEEK_END)
        sz = self._f.tell()
        self._f.seek(pos)
        return sz

    def seek(self, seekBytes, iWhence=0):
        return self._f.seek(seekBytes, iWhence)

    def close(self):
        try:
            self._f.close()
        except Exception:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class _FastStorageServer:
    """SQLite-backed StorageServer replacement so addons never stall on Kodi TCP cache sockets."""

    def __init__(self, table=None, timeout=24):
        self.table_name = str(table or "default")
        self.timeout = float(timeout or 24)

    def _db_path(self):
        p = os.path.join(_STATE.kodi_env, "temp", "brootlite_plugin_cache.db")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        return p

    def _conn(self):
        c = sqlite3.connect(self._db_path(), timeout=5)
        c.execute(
            "CREATE TABLE IF NOT EXISTS cache_store (tbl TEXT, k TEXT, v TEXT, ts REAL, PRIMARY KEY (tbl, k))"
        )
        return c

    def cacheFunction(self, funct=False, *args):
        if not funct:
            return []
        key_raw = repr((getattr(funct, "__name__", "fn"), args))
        key = hashlib.sha256(key_raw.encode("utf-8", errors="ignore")).hexdigest()
        now = time.time()
        max_age = self.timeout * 3600.0
        try:
            with self._conn() as conn:
                row = conn.execute(
                    "SELECT v, ts FROM cache_store WHERE tbl=? AND k=?",
                    (self.table_name, key),
                ).fetchone()
                if row and (now - float(row[1])) < max_age:
                    return json.loads(row[0])
        except Exception:
            pass

        result = funct(*args)
        if result:
            try:
                serialized = json.dumps(result)
                with self._conn() as conn:
                    conn.execute(
                        "INSERT OR REPLACE INTO cache_store (tbl, k, v, ts) VALUES (?, ?, ?, ?)",
                        (self.table_name, key, serialized, now),
                    )
            except Exception:
                pass
        return result

    def cacheDelete(self, name):
        try:
            with self._conn() as conn:
                conn.execute("DELETE FROM cache_store WHERE tbl=?", (self.table_name,))
        except Exception:
            pass
        return True

    def cacheClean(self, empty=False):
        return self.cacheDelete("%")

    def set(self, name, data):
        try:
            with self._conn() as conn:
                conn.execute(
                    "INSERT OR REPLACE INTO cache_store (tbl, k, v, ts) VALUES (?, ?, ?, ?)",
                    (self.table_name, str(name), json.dumps(data), time.time()),
                )
        except Exception:
            pass
        return ""

    def get(self, name):
        try:
            with self._conn() as conn:
                row = conn.execute(
                    "SELECT v FROM cache_store WHERE tbl=? AND k=?",
                    (self.table_name, str(name)),
                ).fetchone()
                if row:
                    return json.loads(row[0])
        except Exception:
            pass
        return ""

    def setMulti(self, name, data):
        return self.set(name, data)

    def getMulti(self, name, items):
        return self.get(name) or []

    def lock(self, name):
        return True

    def unlock(self, name):
        return True


def _install_kodi_modules():
    # 1. xbmc
    xbmc = types.ModuleType("xbmc")
    xbmc.LOGDEBUG = 0
    xbmc.LOGINFO = 1
    xbmc.LOGNOTICE = 1
    xbmc.LOGWARNING = 2
    xbmc.LOGERROR = 3
    xbmc.LOGSEVERE = 4
    xbmc.LOGFATAL = 4
    xbmc.LOGNONE = 5
    xbmc.ISO_639_1 = 0
    xbmc.ISO_639_2 = 1
    xbmc.ENGLISH_NAME = 2

    def _log(msg, level=1):
        print(f"[KodiAddon:{level}] {msg}")

    def _executebuiltin(function, wait=False):
        cmd = str(function or "")
        if "Container.Refresh" in cmd:
            _STATE.refresh = True
        else:
            m = re.search(r"(?:Container\.Update|ActivateWindow)\((?:[^,]+,\s*)?[\"']?(plugin://[^\"')\s,]+)", cmd)
            if m:
                _STATE.redirect_url = m.group(1)
            else:
                m_run = re.search(r"RunPlugin\([\"']?(plugin://[^\"')]+)", cmd)
                if m_run:
                    _STATE.redirect_url = m_run.group(1)

    xbmc.log = _log
    xbmc.sleep = lambda ms: time.sleep(max(0.0, float(ms) / 1000.0))
    xbmc.translatePath = _translate_path
    xbmc.makeLegalFilename = lambda filename, fatX=True: _translate_path(filename)
    xbmc.validatePath = lambda path: _translate_path(path)
    xbmc.executebuiltin = _executebuiltin
    xbmc.executeJSONRPC = lambda rpc: json.dumps({"id": 1, "jsonrpc": "2.0", "result": {"addon": {"enabled": False}}})
    xbmc.getSkinDir = lambda: "skin.estuary"
    xbmc.getInfoLabel = lambda cLine: "21.2" if "BuildVersion" in str(cLine) else ""
    xbmc.getInfoImage = lambda cTag: ""
    xbmc.getCondVisibility = lambda condition: False
    xbmc.getLanguage = lambda format=2, region=False: "en" if format == 0 else "English"
    xbmc.getSupportedMedia = lambda mediaType: ".mp4|.mkv|.avi|.mov|.webm|.m3u8|.ts"
    xbmc.convertLanguage = lambda language, format: "en"
    xbmc.VideoStreamDetail = _VideoStreamDetail
    xbmc.Keyboard = _Keyboard
    xbmc.Player = _Player
    xbmc.Monitor = _Monitor
    xbmc.PlayList = lambda playList=1: types.SimpleNamespace(clear=lambda: None, add=lambda *a, **k: None, size=lambda: 0)
    xbmc.PLAYLIST_VIDEO = 1
    xbmc.PLAYLIST_MUSIC = 0

    # 2. xbmcgui
    xbmcgui = types.ModuleType("xbmcgui")
    xbmcgui.ListItem = _ListItem
    xbmcgui.Dialog = _Dialog
    xbmcgui.DialogProgress = _DialogProgress
    xbmcgui.DialogProgressBG = _DialogProgress
    xbmcgui.Window = _Window
    xbmcgui.WindowDialog = _Window
    xbmcgui.WindowXML = _Window
    xbmcgui.WindowXMLDialog = _Window
    xbmcgui.getCurrentWindowId = lambda: 10025
    xbmcgui.getCurrentWindowDialogId = lambda: 9999
    xbmcgui.NOTIFICATION_INFO = "info"
    xbmcgui.NOTIFICATION_WARNING = "warning"
    xbmcgui.NOTIFICATION_ERROR = "error"
    xbmcgui.INPUT_ALPHANUM = 0
    xbmcgui.INPUT_NUMERIC = 1
    xbmcgui.PASSWORD_VERIFY = 1
    xbmcgui.ALPHANUM_HIDE_INPUT = 2

    # 3. xbmcplugin
    xbmcplugin = types.ModuleType("xbmcplugin")
    xbmcplugin.SORT_METHOD_NONE = 0
    xbmcplugin.SORT_METHOD_LABEL = 1
    xbmcplugin.SORT_METHOD_LABEL_IGNORE_THE = 2
    xbmcplugin.SORT_METHOD_DATE = 3
    xbmcplugin.SORT_METHOD_SIZE = 4
    xbmcplugin.SORT_METHOD_FILE = 5
    xbmcplugin.SORT_METHOD_DURATION = 8
    xbmcplugin.SORT_METHOD_TITLE = 9
    xbmcplugin.SORT_METHOD_TITLE_IGNORE_THE = 10
    xbmcplugin.SORT_METHOD_GENRE = 16
    xbmcplugin.SORT_METHOD_YEAR = 18
    xbmcplugin.SORT_METHOD_VIDEO_RATING = 19
    xbmcplugin.SORT_METHOD_EPISODE = 24
    xbmcplugin.SORT_METHOD_VIDEO_SORT_TITLE = 29
    xbmcplugin.SORT_METHOD_UNSORTED = 40

    def _add_directory_item(handle, url, listitem, isFolder=False, totalItems=0):
        if isinstance(listitem, _ListItem):
            _STATE.items.append(listitem.to_dict(url=str(url or ""), is_folder=bool(isFolder)))
        return True

    def _add_directory_items(handle, items, totalItems=0):
        if isinstance(items, (list, tuple)):
            for entry in items:
                if len(entry) >= 2:
                    url = entry[0]
                    li = entry[1]
                    is_f = entry[2] if len(entry) >= 3 else False
                    _add_directory_item(handle, url, li, is_f)
        return True

    def _set_resolved_url(handle, succeeded, listitem):
        if succeeded and isinstance(listitem, _ListItem):
            _STATE.resolved_item = listitem.to_dict(url=listitem.getPath(), is_folder=False)

    xbmcplugin.addDirectoryItem = _add_directory_item
    xbmcplugin.addDirectoryItems = _add_directory_items
    xbmcplugin.endOfDirectory = lambda handle, succeeded=True, updateListing=False, cacheToDisc=True: None
    xbmcplugin.setResolvedUrl = _set_resolved_url
    xbmcplugin.setContent = lambda handle, content: setattr(_STATE, "content_type", str(content or "videos"))
    xbmcplugin.addSortMethod = lambda handle, sortMethod, labelMask="", label2Mask="": None
    xbmcplugin.setPluginCategory = lambda handle, category: setattr(_STATE, "category", str(category or ""))
    xbmcplugin.setPluginFanart = lambda handle, image=None, color1=None, color2=None, color3=None: None
    xbmcplugin.setProperty = lambda handle, key, value: None

    # 4. xbmcaddon
    xbmcaddon = types.ModuleType("xbmcaddon")
    xbmcaddon.Addon = _KodiAddon

    # 5. xbmcvfs
    xbmcvfs = types.ModuleType("xbmcvfs")
    xbmcvfs.translatePath = _translate_path
    xbmcvfs.makeLegalFilename = lambda filename: _translate_path(filename)
    xbmcvfs.validatePath = lambda path: _translate_path(path)
    xbmcvfs.exists = lambda path: os.path.exists(_translate_path(path))

    def _mkdir(path):
        try:
            os.makedirs(_translate_path(path), exist_ok=True)
            return True
        except Exception:
            return False

    def _rmdir(path, force=False):
        p = _translate_path(path)
        try:
            if force:
                shutil.rmtree(p, ignore_errors=True)
            else:
                os.rmdir(p)
            return True
        except Exception:
            return False

    def _delete(file_path):
        try:
            os.remove(_translate_path(file_path))
            return True
        except Exception:
            return False

    def _copy(strSource, strDestination):
        try:
            shutil.copy2(_translate_path(strSource), _translate_path(strDestination))
            return True
        except Exception:
            return False

    def _rename(file, newFileName):
        try:
            os.replace(_translate_path(file), _translate_path(newFileName))
            return True
        except Exception:
            return False

    def _listdir(path):
        p = _translate_path(path)
        dirs, files = [], []
        if os.path.isdir(p):
            for entry in os.listdir(p):
                full = os.path.join(p, entry)
                if os.path.isdir(full):
                    dirs.append(entry)
                else:
                    files.append(entry)
        return dirs, files

    class _Stat:
        def __init__(self, path):
            self._st = os.stat(_translate_path(path))

        def st_mtime(self):
            return int(self._st.st_mtime)

        def st_size(self):
            return int(self._st.st_size)

    xbmcvfs.mkdir = _mkdir
    xbmcvfs.mkdirs = _mkdir
    xbmcvfs.rmdir = _rmdir
    xbmcvfs.delete = _delete
    xbmcvfs.copy = _copy
    xbmcvfs.rename = _rename
    xbmcvfs.listdir = _listdir
    xbmcvfs.File = _VfsFile
    xbmcvfs.Stat = _Stat

    # 6. kodi_six & kodi_six.utils
    kodi_six = types.ModuleType("kodi_six")
    kodi_six.xbmc = xbmc
    kodi_six.xbmcgui = xbmcgui
    kodi_six.xbmcplugin = xbmcplugin
    kodi_six.xbmcaddon = xbmcaddon
    kodi_six.xbmcvfs = xbmcvfs

    kodi_six_utils = types.ModuleType("kodi_six.utils")
    kodi_six_utils.py2_encode = lambda s, *a, **kw: s
    kodi_six_utils.py2_decode = lambda s, *a, **kw: s
    kodi_six.utils = kodi_six_utils

    # 7. StorageServer & inputstreamhelper fast shims
    storage_mod = types.ModuleType("StorageServer")
    storage_mod.StorageServer = _FastStorageServer

    ish_mod = types.ModuleType("inputstreamhelper")

    class _IshHelper:
        def __init__(self, protocol, drm=None):
            self.protocol = protocol
            self.drm = drm

        def check_inputstream(self):
            return False

    ish_mod.Helper = _IshHelper

    sys.modules["xbmc"] = xbmc
    sys.modules["xbmcgui"] = xbmcgui
    sys.modules["xbmcplugin"] = xbmcplugin
    sys.modules["xbmcaddon"] = xbmcaddon
    sys.modules["xbmcvfs"] = xbmcvfs
    sys.modules["kodi_six"] = kodi_six
    sys.modules["kodi_six.utils"] = kodi_six_utils
    sys.modules["StorageServer"] = storage_mod
    sys.modules["inputstreamhelper"] = ish_mod


def _setup_module_paths(kodi_env):
    addons_dir = os.path.join(kodi_env, "addons")
    if not os.path.isdir(addons_dir):
        return
    for entry in sorted(os.listdir(addons_dir)):
        if entry in ("script.module.kodi-six", "script.common.plugin.cache", "script.module.inputstreamhelper"):
            # Handled natively by our fast synthetic modules above
            continue
        mod_root = os.path.join(addons_dir, entry)
        if not os.path.isdir(mod_root):
            continue
        addon_xml = os.path.join(mod_root, "addon.xml")
        added = False
        if os.path.isfile(addon_xml):
            try:
                root = ET.parse(addon_xml).getroot()
                for ext in root.findall("extension"):
                    if ext.get("point") == "xbmc.python.module":
                        lib_rel = ext.get("library", "lib")
                        lib_path = os.path.normpath(os.path.join(mod_root, lib_rel))
                        if os.path.isdir(lib_path) and lib_path not in sys.path:
                            sys.path.append(lib_path)
                            added = True
            except Exception:
                pass
        if not added and entry.startswith("script."):
            fallback_lib = os.path.join(mod_root, "lib")
            if os.path.isdir(fallback_lib) and fallback_lib not in sys.path:
                sys.path.append(fallback_lib)


def _find_entry_script(addon_dir):
    addon_xml = os.path.join(addon_dir, "addon.xml")
    if os.path.isfile(addon_xml):
        try:
            root = ET.parse(addon_xml).getroot()
            for ext in root.findall("extension"):
                if ext.get("point") == "xbmc.python.pluginsource":
                    lib = ext.get("library", "default.py")
                    cand = os.path.join(addon_dir, lib)
                    if os.path.isfile(cand):
                        return cand
        except Exception:
            pass
    for fallback in ("default.py", "addon.py", "plugin.py", "main.py"):
        cand = os.path.join(addon_dir, fallback)
        if os.path.isfile(cand):
            return cand
    return ""


def list_video_addons(kodi_env):
    addons_dir = os.path.join(str(kodi_env), "addons")
    results = []
    if not os.path.isdir(addons_dir):
        return json.dumps(results)
    for entry in sorted(os.listdir(addons_dir)):
        if not entry.startswith("plugin.video."):
            continue
        addon_dir = os.path.join(addons_dir, entry)
        addon_xml = os.path.join(addon_dir, "addon.xml")
        if not os.path.isfile(addon_xml):
            continue
        try:
            root = ET.parse(addon_xml).getroot()
            aid = root.get("id", entry)
            name = root.get("name", entry)
            version = root.get("version", "1.0")
            summary_el = root.find(".//summary")
            summary = summary_el.text if summary_el is not None and summary_el.text else ""
            icon_el = root.find(".//assets/icon")
            icon = os.path.join(addon_dir, icon_el.text.strip()) if icon_el is not None and icon_el.text else os.path.join(addon_dir, "icon.png")
            results.append({
                "id": aid,
                "name": _strip_bbcode(name),
                "version": version,
                "summary": _strip_bbcode(summary),
                "icon": icon if os.path.isfile(icon) else "",
                "pluginUrl": f"plugin://{aid}/",
            })
        except Exception:
            pass
    return json.dumps(results)


def execute_plugin(plugin_url, kodi_env, callback=None):
    """
    Executes a Kodi plugin:// URL and returns a JSON response:
    {
      "ok": true,
      "addonId": "...",
      "category": "...",
      "items": [...],
      "resolved": {...} | null,
      "redirectUrl": "..." | null,
      "refresh": bool,
      "error": null
    }
    """
    try:
        kodi_env = str(kodi_env).rstrip("/")
        for sub in ("temp", "userdata/Database", "userdata/Thumbnails", "userdata/addon_data"):
            os.makedirs(os.path.join(kodi_env, sub), exist_ok=True)

        parsed = urllib.parse.urlparse(str(plugin_url))
        addon_id = parsed.netloc
        path_part = parsed.path or "/"
        query_part = ("?" + parsed.query) if parsed.query else ""

        _install_kodi_modules()
        if not _STATE.sys_paths_initialized:
            _setup_module_paths(kodi_env)
            _STATE.sys_paths_initialized = True

        # Virtual helper: resolve hoster/embed externalUrl mirrors natively via script.module.resolveurl
        if addon_id == "__resolveurl__":
            qs = urllib.parse.parse_qs(parsed.query)
            target_url = (qs.get("url") or [""])[0].strip()
            _STATE.reset_request(kodi_env, "script.module.resolveurl", kodi_env, callback)
            resolved_stream = None
            err_msg = None
            if target_url:
                try:
                    import resolveurl
                    hmf = resolveurl.HostedMediaFile(url=target_url, include_universal=True)
                    if hmf:
                        s_url = hmf.resolve()
                        if s_url and isinstance(s_url, str):
                            resolved_stream = {
                                "label": "Resolved Mirror",
                                "cleanLabel": "Resolved Mirror",
                                "url": s_url,
                                "isFolder": False,
                                "isPlayable": True,
                                "thumb": "",
                                "fanart": "",
                                "plot": "",
                                "duration": 0,
                                "width": 0,
                                "height": 0,
                                "mimeType": "",
                                "subtitles": [],
                                "contextMenu": [],
                            }
                except Exception as r_exc:
                    err_msg = str(r_exc)
            return json.dumps({
                "ok": resolved_stream is not None,
                "addonId": "__resolveurl__",
                "category": "",
                "items": [],
                "resolved": resolved_stream,
                "redirectUrl": None,
                "refresh": False,
                "error": err_msg if resolved_stream is None else None,
            })

        addon_dir = os.path.join(kodi_env, "addons", addon_id)
        if not os.path.isdir(addon_dir):
            return json.dumps({
                "ok": False,
                "addonId": addon_id,
                "items": [],
                "resolved": None,
                "redirectUrl": None,
                "refresh": False,
                "error": f"Addon '{addon_id}' not found in {addon_dir}. Ensure Kodi environment is synced to BrootLite.",
            })

        # Purge conflicting top-level addon packages when switching between addons (when switching between video addons)
        if _STATE.last_addon_id and _STATE.last_addon_id != addon_id:
            prev_dir = os.path.join(kodi_env, "addons", _STATE.last_addon_id)
            if prev_dir in sys.path:
                sys.path.remove(prev_dir)
            for mod_key in list(sys.modules.keys()):
                if mod_key == "resources" or mod_key.startswith("resources.") or mod_key.startswith("custom_"):
                    sys.modules.pop(mod_key, None)

        if addon_dir in sys.path:
            sys.path.remove(addon_dir)
        sys.path.insert(0, addon_dir)

        _STATE.reset_request(kodi_env, addon_id, addon_dir, callback)
        _STATE.last_addon_id = addon_id

        entry_script = _find_entry_script(addon_dir)
        if not entry_script:
            return json.dumps({
                "ok": False,
                "addonId": addon_id,
                "items": [],
                "resolved": None,
                "redirectUrl": None,
                "refresh": False,
                "error": f"No entry script (default.py) found inside {addon_dir}",
            })

        base_plugin_url = f"plugin://{addon_id}{path_part}"
        sys.argv = [base_plugin_url, "1", query_part, "resume:false"]

        # Clear main.* routes if URL_Dispatcher caches routes at class level across runs
        ud_mod = sys.modules.get("resources.lib.url_dispatcher")
        if ud_mod is not None and hasattr(ud_mod, "URL_Dispatcher"):
            ud_cls = ud_mod.URL_Dispatcher
            for attr_name in ("func_registry", "args_registry", "kwargs_registry"):
                reg = getattr(ud_cls, attr_name, None)
                if isinstance(reg, dict):
                    for k in [rk for rk in list(reg.keys()) if str(rk).startswith("main.")]:
                        reg.pop(rk, None)

        prev_cwd = os.getcwd()
        try:
            os.chdir(addon_dir)
            try:
                with open(entry_script, "rb") as script_fp:
                    compiled = compile(script_fp.read(), entry_script, "exec")
                script_globals = {
                    "__name__": "__main__",
                    "__file__": entry_script,
                    "__builtins__": __builtins__,
                    "__package__": None,
                }
                exec(compiled, script_globals)
            except SystemExit:
                # Addons routinely call sys.exit(main()) on normal completion
                pass
        finally:
            try:
                os.chdir(prev_cwd)
            except Exception:
                pass

        return json.dumps({
            "ok": True,
            "addonId": addon_id,
            "category": _STATE.category,
            "items": _STATE.items,
            "resolved": _STATE.resolved_item,
            "redirectUrl": _STATE.redirect_url,
            "refresh": _STATE.refresh,
            "error": None,
        })
    except Exception as exc:
        tb = traceback.format_exc()
        print(f"[BrootLite:kodi_bridge] ERROR executing {plugin_url}:\n{tb}")
        return json.dumps({
            "ok": False,
            "addonId": _STATE.addon_id,
            "items": _STATE.items,
            "resolved": _STATE.resolved_item,
            "redirectUrl": None,
            "refresh": False,
            "error": f"{type(exc).__name__}: {exc}",
        })
