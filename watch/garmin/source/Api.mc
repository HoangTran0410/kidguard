import Toybox.Communications;
import Toybox.Lang;

// Relay client. Every call goes through Garmin Connect on the parent's phone (makeWebRequest); the callback gets
// (responseCode, data): data = parsed JSON Dictionary, or null. Garmin negative codes: -402 / -403 = response too large
// for the watch's memory, -104 = no phone link. Never print headers (the token is in them).
module Api {
    const NAME = "Garmin watch";

    function call(method, url, headers, body, cb) {
        Communications.makeWebRequest(url, body, {
            :method => method,
            :headers => headers,
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        }, cb);
    }

    function json() {
        return { "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON };
    }

    // ---- connect by code (docs/protocol.md "Watch connect") ----

    function connectNew(sh, cb) {
        call(Communications.HTTP_REQUEST_METHOD_POST, Store.relay() + "/w/new", json(), { "sh" => sh }, cb);
    }

    function connectPoll(code, secret, cb) {
        call(Communications.HTTP_REQUEST_METHOD_GET, Store.relay() + "/w/" + code, { "X-KG-Secret" => secret }, null, cb);
    }

    // ---- one kid ----

    function kidUrl(k, path) {
        return k["url"] + "/c/" + k["id"] + path;
    }

    function kidHeaders(k, withBody) {
        var h = { "X-KG-Token" => k["token"], "X-KG-Name" => NAME };
        if (withBody) {
            h["Content-Type"] = Communications.REQUEST_CONTENT_TYPE_JSON;
        }
        return h;
    }

    // ?watch=1 = the small view (~2-6 KB, docs/protocol.md): {state, rules, seen, pending, queue, expired, tileKey}
    function state(k, cb) {
        call(Communications.HTTP_REQUEST_METHOD_GET, kidUrl(k, "/state?watch=1"), kidHeaders(k, false), null, cb);
    }

    // queued only ("sent"): the phone runs it when it next syncs
    function cmd(k, c, cb) {
        call(Communications.HTTP_REQUEST_METHOD_POST, kidUrl(k, "/cmd"), kidHeaders(k, true), c, cb);
    }

    // the WHOLE rules object (full replace on the phone)
    function putRules(k, rules, cb) {
        call(Communications.HTTP_REQUEST_METHOD_PUT, kidUrl(k, "/rules"), kidHeaders(k, true), rules, cb);
    }
}
