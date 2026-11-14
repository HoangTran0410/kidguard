import Toybox.Lang;
import Toybox.WatchUi;

// Answer a kid's request. Same order as the page and the Zepp watch (docs/protocol.md "answer"):
//   approve: GET state -> change the rules -> PUT the whole rules -> POST cmd answer {req, ok, min | always}
//   deny:    POST cmd answer {req, ok:false}
// `req` = {id, kind: "time"|"app", min, pkg, app}.
class Grant {
    var k;
    var req;
    var ok;
    var min;
    var toast;

    function initialize(kid, request, approve, minutes, t) {
        k = kid;
        req = request;
        ok = approve;
        min = minutes;
        toast = t;
    }

    function start() {
        if (!ok) {
            answer();
        } else {
            Api.state(k, method(:onState));
        }
    }

    function isApp() {
        return req["kind"].equals("app");
    }

    function answer() {
        var c = { "type" => "answer", "req" => req["id"], "ok" => ok };
        if (ok) {
            if (isApp()) {
                c["always"] = true;
            } else {
                c["min"] = min;
            }
        }
        Api.cmd(k, c, toast.method(:onResult));
    }

    function onState(code, data) as Void {
        if (code != 200) {
            toast.onResult(code, null);
            return;
        }
        var r = (data instanceof Lang.Dictionary) ? Util.newestRules(data) : null;
        // an unreadable / truncated rules object must never be PUT: the phone replaces its rules with it
        if (r == null || r.size() < 5) {
            toast.set(Util.t(Rez.Strings.RulesBad), false);
            return;
        }
        var s = data["state"];
        if (isApp()) {
            approveApp(r);
        } else {
            grantTime(r, s);
        }
        // never send the PIN material back (the page strips it too)
        r.remove("pinHash");
        r.remove("pinSet");
        r.remove("pin");
        data = null;
        Api.putRules(k, r, method(:onPut));
    }

    function onPut(code, data) as Void {
        if (code >= 200 && code < 300) {
            answer();
        } else {
            toast.onResult(code, data);
        }
    }

    // same rule as the page's grantApply: no lock or daily -> today's bonus minutes; any other lock -> a timed pass
    function grantTime(r, s) {
        var day = "";
        var why = "";
        if (s instanceof Lang.Dictionary) {
            day = Util.str(s["day"], 12);
            var now = s["now"];
            if (now instanceof Lang.Dictionary) {
                why = Util.str(now["why"], 16);
            }
        }
        if (why.equals("") || why.equals("daily")) {
            var b = r["bonus"];
            var c0 = 0;
            if (b instanceof Lang.Dictionary && day.equals(b["day"])) {
                c0 = Util.lng(b["min"]).toNumber();
            }
            var total = c0 + min;
            r["bonus"] = { "day" => day, "min" => total > 600 ? 600 : total };
        } else {
            var base = Util.nowMs();
            var cur = Util.lng(r["passUntil"]);
            if (cur > base) {
                base = cur;
            }
            r["passUntil"] = base + min.toLong() * 60000l;
        }
    }

    // app request: approved += pkg, blocked -= pkg
    function approveApp(r) {
        var pkg = req["pkg"];
        var ap = r["approved"];
        var has = false;
        if (!(ap instanceof Lang.Array)) {
            ap = [];
        }
        for (var i = 0; i < ap.size(); i++) {
            if (pkg.equals(ap[i])) {
                has = true;
            }
        }
        if (!has) {
            ap.add(pkg);
        }
        r["approved"] = ap;
        var bl = r["blocked"];
        var out = [];
        if (bl instanceof Lang.Array) {
            for (var j = 0; j < bl.size(); j++) {
                if (!pkg.equals(bl[j])) {
                    out.add(bl[j]);
                }
            }
        }
        r["blocked"] = out;
    }
}
