import Toybox.Application;
import Toybox.Lang;

// Everything the watch remembers lives in Application.Storage (survives app restarts, per watch):
//   kids   Array of {url, id, token, name}   (the token is the parent's relay token: never print it)
//   wsecret / wcode / wexp   the pending connect attempt (secret stays on the watch; the relay only sees its sha256)
//   zoom   map zoom 13..18
module Store {
    const DEFAULT_RELAY = "https://kidguard.99-hoangtran.workers.dev";

    function get(key) {
        try {
            return Application.Storage.getValue(key);
        } catch (e) {
            return null;
        }
    }

    function put(key, v) {
        try {
            if (v == null) {
                Application.Storage.deleteValue(key);
            } else {
                Application.Storage.setValue(key, v);
            }
        } catch (e) {
        }
    }

    // relay base URL: the app setting (Garmin Connect) when it is a plain https origin, else the author's shared relay
    function relay() {
        var r = null;
        try {
            r = Application.Properties.getValue("relay");
        } catch (e) {
        }
        if (r instanceof Lang.String) {
            while (r.length() > 0 && r.substring(r.length() - 1, r.length()).equals("/")) {
                r = r.substring(0, r.length() - 1);
            }
            if (r.length() > 10 && r.substring(0, 8).equals("https://") && r.find("?") == null && r.find("#") == null && r.find(" ") == null) {
                // an origin only: no path after the host
                if (r.substring(8, r.length()).find("/") == null) {
                    return r;
                }
            }
        }
        return DEFAULT_RELAY;
    }

    function kids() {
        var k = get("kids");
        return (k instanceof Lang.Array) ? k : [];
    }

    function kid(i) {
        var a = kids();
        if (i < 0 || i >= a.size()) {
            return null;
        }
        return a[i];
    }

    // same kid id again = replace (re-connect after "renew relay link")
    function addKid(k) {
        var a = kids();
        var out = [];
        for (var i = 0; i < a.size(); i++) {
            if (!a[i]["id"].equals(k["id"])) {
                out.add(a[i]);
            }
        }
        out.add(k);
        put("kids", out);
    }

    function removeKid(i) {
        var a = kids();
        var out = [];
        for (var j = 0; j < a.size(); j++) {
            if (j != i) {
                out.add(a[j]);
            }
        }
        put("kids", out);
    }

    function zoom() {
        var z = get("zoom");
        if (z instanceof Lang.Number && z >= 13 && z <= 18) {
            return z;
        }
        return 16;
    }
}
