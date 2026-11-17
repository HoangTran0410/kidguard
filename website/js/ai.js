/* "Ask an AI": copies a prompt that sends any chat AI to /ai/<lang>/ (one line per guide section, written by
   scripts/gen-ai-guide.mjs from the guide), then only to the sections it needs. Not llms.txt: Gemini said it "can't
   access" that link while ChatGPT read it; it reads the same kind of HTML pages on tvremote.pages.dev (owner 2026-11-17).
   On a guide section (#id), that section's page goes along. */
(function(){
  "use strict";
  var toast = document.getElementById("toast"), tt;
  function say(s){ toast.textContent = s; toast.classList.add("show"); clearTimeout(tt); tt = setTimeout(function(){ toast.classList.remove("show"); }, 2200); }
  function aiPrompt(vi){
    var url = location.origin + "/ai/" + (vi ? "vi" : "en") + "/", id = location.hash.slice(1);
    var here = id && /guide/.test(location.pathname) && document.getElementById(id) && document.getElementById(id).tagName === "SECTION" ? url + id : "";
    return vi
      ? "Tôi có câu hỏi về KidGuard (app quản lý điện thoại Android của con: dùng được ngay sau khi cài, mạnh nhất khi bật Device Owner; bố mẹ quản lý bằng trình duyệt).\n"
        + "Hướng dẫn cho AI: " + url + " (mỗi mục một dòng). Đọc trang đó trước, rồi chỉ mở trang của mục cần dùng.\n"
        + (here ? "Tôi đang xem mục: " + here + "\n" : "")
        + "Trả lời đúng việc tôi hỏi, theo tài liệu. Cần biết điện thoại của con (hãng, bản Android) thì hỏi. Có nhiều bước thì đưa từng bước, nói rõ làm trên máy của con hay máy bố mẹ, chờ tôi báo xong mới sang bước tiếp.\n\n"
        + "Câu hỏi của tôi: "
      : "I have a question about KidGuard (an Android parental-control app: works right after install, strongest as Device Owner; parents manage it from a browser).\n"
        + "The guide for AI: " + url + " (one line per topic). Read it first, then open only the topics you need.\n"
        + (here ? "I am reading: " + here + "\n" : "")
        + "Answer what I ask, from the document. Ask about the child's phone (brand, Android version) only when it matters. For several steps, give one at a time, saying whether it happens on the child's phone or the parent's, and wait until I say it worked.\n\n"
        + "My question: ";
  }
  [].forEach.call(document.querySelectorAll(".gai"), function(b){
    b.addEventListener("click", function(){
      var vi = document.documentElement.lang === "vi", p = aiPrompt(vi);
      (navigator.clipboard ? navigator.clipboard.writeText(p) : Promise.reject())
        .then(function(){ say(vi ? "Đã chép. Dán vào ChatGPT, Claude, Gemini…" : "Copied. Paste it into ChatGPT, Claude, Gemini…"); })
        .catch(function(){ window.prompt(vi ? "Chép đoạn này cho AI:" : "Copy this for your AI:", p); });
    });
  });
})();
