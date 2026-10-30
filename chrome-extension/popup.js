const $ = id => document.getElementById(id);
const defaults = {mode:"template",templates:"",apiKey:"",tone:"Friendly, relevant, natural",minLikes:0,minComments:0};
async function load(){const s={...defaults,...await chrome.storage.local.get(defaults)};for(const k of Object.keys(s)){if($(k))$(k).value=s[k]}toggle();}
function toggle(){$("templateSection").hidden=$("mode").value!=="template";$("aiSection").hidden=$("mode").value!=="ai";}
$("mode").addEventListener("change",toggle);
$("save").addEventListener("click",async()=>{const data={mode:$("mode").value,templates:$("templates").value,apiKey:$("apiKey").value.trim(),tone:$("tone").value,minLikes:Math.max(0,Number($("minLikes").value)||0),minComments:Math.max(0,Number($("minComments").value)||0)};await chrome.storage.local.set(data);$("status").textContent="Settings saved on this device.";});
load();