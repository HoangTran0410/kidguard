import Toybox.Lang;
import Toybox.StringUtil;
import Toybox.Cryptography;
import Toybox.Time;
import Toybox.Math;
import Toybox.WatchUi;

module Util {
    const HEXD = "0123456789abcdef";

    function t(id) {
        return WatchUi.loadResource(id);
    }

    // "$1$ min ago" style resource with one number / string
    function f(id, v) {
        return Lang.format(t(id), [v]);
    }

    function hex(b) {
        var s = "";
        for (var i = 0; i < b.size(); i++) {
            var v = b[i] & 0xFF;
            var hi = v >> 4;
            var lo = v & 15;
            s += HEXD.substring(hi, hi + 1) + HEXD.substring(lo, lo + 1);
        }
        return s;
    }

    // sha256 of the ASCII text, lowercase hex (same as the Zepp app: it hashes the hex secret as text)
    function sha256hex(text) {
        var bytes = StringUtil.convertEncodedString(text, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :encoding => StringUtil.CHAR_ENCODING_UTF8
        });
        var h = new Cryptography.Hash({ :algorithm => Cryptography.HASH_SHA256 });
        h.update(bytes);
        return hex(h.digest());
    }

    // 64 hex chars from the secure random generator
    function randomSecret() {
        return hex(Cryptography.randomBytes(32));
    }

    // JSON numbers like epoch ms do not fit a 32-bit Number: always read them as Long
    function lng(v) {
        if (v == null) {
            return 0l;
        }
        try {
            return v.toLong();
        } catch (e) {
            return 0l;
        }
    }

    function nowMs() {
        return Time.now().value().toLong() * 1000l;
    }

    function str(v, max) {
        if (!(v instanceof Lang.String)) {
            return "";
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    // the rules to edit: the newer (rev) of j.rules and j.state.rules, same rule as the Zepp app
    function newestRules(j) {
        var a = j["rules"];
        var s = j["state"];
        var b = (s instanceof Lang.Dictionary) ? s["rules"] : null;
        var aok = a instanceof Lang.Dictionary;
        var bok = b instanceof Lang.Dictionary;
        if (aok && bok) {
            return lng(a["rev"]) >= lng(b["rev"]) ? a : b;
        }
        if (aok) {
            return a;
        }
        if (bok) {
            return b;
        }
        return null;
    }

    // "1h25" / "45m"
    function dur(ms) {
        var m = (ms / 60000l).toNumber();
        if (m < 0) {
            m = 0;
        }
        if (m >= 60) {
            return (m / 60).toString() + "h" + (m % 60).format("%02d");
        }
        return m.toString() + "m";
    }

    function ago(ms) {
        if (ms <= 0l) {
            return "-";
        }
        var d = ((nowMs() - ms) / 1000l).toNumber();
        if (d < 90) {
            return t(Rez.Strings.AgoNow);
        }
        if (d < 3600) {
            return f(Rez.Strings.AgoMin, d / 60);
        }
        if (d < 86400) {
            return f(Rez.Strings.AgoHour, d / 3600);
        }
        return f(Rez.Strings.AgoDay, d / 86400);
    }

    function whyText(why) {
        if (why.equals("lost")) { return t(Rez.Strings.WhyLost); }
        if (why.equals("paused")) { return t(Rez.Strings.WhyPaused); }
        if (why.equals("schedule")) { return t(Rez.Strings.WhySchedule); }
        if (why.equals("bedtime") || why.equals("bed")) { return t(Rez.Strings.WhyBed); }
        if (why.equals("daily")) { return t(Rez.Strings.WhyDaily); }
        if (why.equals("pass")) { return t(Rez.Strings.WhyPass); }
        if (why.equals("code")) { return t(Rez.Strings.WhyCode); }
        return t(Rez.Strings.WhyOther);
    }

    // user text for a failed request (code = HTTP status or a Garmin negative code)
    function failText(code, data) {
        if (code == -402 || code == -403) {
            return t(Rez.Strings.ErrTooLarge);
        }
        if (code == 401 || code == 409) {
            return t(Rez.Strings.ErrAuth);
        }
        if (code < 0) {
            return t(Rez.Strings.NoNet) + " (" + code.toString() + ")";
        }
        return f(Rez.Strings.ErrHttp, code);
    }

    // OSM slippy tile of (lat, lon) at zoom z: [x, y, fx, fy] with fx/fy = position inside the tile (0..1).
    // Doubles for the big numbers: a 32-bit Float would be 1-4 px off at z16-18.
    function tileOf(lat, lon, z) {
        var n = (1 << z).toDouble();
        var xf = (lon.toDouble() + 180.0d) / 360.0d * n;
        var latR = lat.toDouble() * Math.PI / 180.0d;
        var yf = (1.0d - Math.ln(Math.tan(latR) + 1.0d / Math.cos(latR)) / Math.PI) / 2.0d * n;
        var x = xf.toNumber();
        var y = yf.toNumber();
        return [x, y, (xf - x.toDouble()).toFloat(), (yf - y.toDouble()).toFloat()];
    }

    // metres per tile pixel (256 px tile) at this latitude and zoom
    function metersPerPixel(lat, z) {
        return 156543.03392 * Math.cos(lat.toDouble() * Math.PI / 180.0d) / (1 << z).toDouble();
    }
}
