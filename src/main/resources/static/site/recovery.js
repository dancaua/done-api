import {copy} from './recovery-copy.js';
const names={en:'English',ro:'Română',es:'Español',it:'Italiano',fr:'Français',de:'Deutsch',pl:'Polski',hi:'हिन्दी',ja:'日本語'};
const params=new URLSearchParams(location.search), fragment=new URLSearchParams(location.hash.slice(1));
let token=fragment.get('token')??'';
// Remove the capability from browser history immediately. Never store it in local/session storage.
if(location.hash)history.replaceState(null,'',location.pathname+location.search);
let language=params.get('lang')??navigator.language.split('-')[0];if(!Object.hasOwn(copy,language))language='en';
const appearance=params.get('appearance');
if(['light','dark'].includes(appearance))document.documentElement.dataset.appearance=appearance;
const pageURL=path=>path+'?'+new URLSearchParams({lang:language,...(['light','dark'].includes(appearance)?{appearance}:{})});
const reset=location.pathname==='/reset-password';let busy=false,finished=false;
const t=key=>copy[language][key];
const node=(tag,text,attrs={})=>{const el=document.createElement(tag);if(text!==undefined)el.textContent=text;for(const[k,v]of Object.entries(attrs))el.setAttribute(k,v);return el;};
const select=document.getElementById('language');for(const[k,v]of Object.entries(names))select.append(node('option',v,{value:k}));select.value=language;
select.addEventListener('change',()=>{language=select.value;history.replaceState(null,'',pageURL(location.pathname));render();});
function render(){
 document.documentElement.lang=language;document.title=t(reset?'reset':'forgot')+' · DONE';document.getElementById('support').textContent=t('support');document.getElementById('support').href=pageURL('/support');document.querySelector('.brand').href=pageURL('/');select.setAttribute('aria-label',names[language]);
 const content=document.getElementById('content');content.replaceChildren(node('h1',t(reset?'reset':'forgot')));
 if(finished){content.append(node('p',t(reset?'success':'accepted'),{class:'success',role:'status'}));return;}
 const article=node('article');content.append(article);
 if(reset&&!/^[A-Za-z0-9_-]{43}$/.test(token)){article.append(node('p',t('invalid')),node('a',t('forgot'),{href:pageURL('/forgot-password')}));return;}
 article.append(node('p',t(reset?'rule':'intro'),{class:'muted'}));const form=node('form');article.append(form);
 const field=(key,type,autocomplete)=>{const input=node('input',undefined,{id:key,type,autocomplete,required:'',name:key});form.append(node('label',t(key),{for:key}),input);return input;};
 let email,password,confirm;
 if(reset){password=field('password','password','new-password');password.minLength=12;password.maxLength=64;confirm=field('confirm','password','new-password');confirm.maxLength=64;}
 else {email=field('email','email','email');email.maxLength=254;}
 const button=node('button',t(reset?'save':'send'),{type:'submit'}),error=node('p',undefined,{role:'alert',id:'error'});button.disabled=busy;form.append(button,error);
 form.addEventListener('submit',async event=>{event.preventDefault();if(busy)return;error.textContent='';
  if(reset&&password.value!==confirm.value){error.textContent=t('mismatch');return;}
  if(reset&&new TextEncoder().encode(password.value).length>72){error.textContent=t('rule');return;}
  busy=true;button.disabled=true;
  try{const response=await fetch('/api/v1/auth/'+(reset?'reset-password':'forgot-password'),{method:'POST',credentials:'omit',cache:'no-store',headers:{'Content-Type':'application/json'},body:JSON.stringify(reset?{token,newPassword:password.value}:{email:email.value}),signal:AbortSignal.timeout(15000)});
   if(response.ok){finished=true;token='';form.reset();render();}
   else {const value=await response.json().catch(()=>({}));error.textContent=t(response.status===429?'busy':value.code==='invalid_reset_token'?'invalid':'error');if(value.code==='invalid_reset_token')error.append(node('br'),node('a',t('forgot'),{href:pageURL('/forgot-password')}));}
  }catch{error.textContent=t('error');}finally{busy=false;button.disabled=false;}
 });
}
window.addEventListener('pagehide',()=>{token='';document.querySelector('form')?.reset();});
window.addEventListener('pageshow',event=>{if(event.persisted)render();});render();
