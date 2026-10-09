import { copy, languageNames } from './copy.js';

const el=id=>document.getElementById(id);
let language=new URL(location.href).searchParams.get('lang') ?? navigator.languages?.map(l=>l.slice(0,2)).find(l=>copy[l]) ?? 'en';
if(!copy[language]) language='en';
let configuration, authentication, password='', busy=false, deleted=false;
const t=(key,args=[])=>copy[language][key].replace(/\{(\d+)\}/g,(_,n)=>String(args[Number(n)]??`{${n}}`));
const paths={privacy:'/privacy',support:'/support',contact:'/contact',delete:'/delete-account'};
const page=location.pathname.includes('privacy')?'privacy':location.pathname.includes('support')?'support':location.pathname.includes('contact')?'contact':location.pathname.includes('delet')?'delete':'home';
function node(tag,text,attributes={}){const result=document.createElement(tag);if(text!==undefined)result.textContent=text;for(const[key,value]of Object.entries(attributes))result.setAttribute(key,value);return result;}
const paragraph=(key,args)=>node('p',t(key,args));
function article(title,key,args){const result=node('article');result.append(node('h2',t(title)),paragraph(key,args));return result;}
function link(key,path){return node('a',t(key),{href:`${path}?lang=${language}`});}
function button(key,action,classes=''){const result=node('button',t(key),{type:'button',class:classes});result.disabled=busy;result.addEventListener('click',action);return result;}
function facts(values){const dl=node('dl');for(const[key,value]of values)dl.append(node('dt',t(key)),node('dd',value));return dl;}
function error(key){el('error').textContent=t(key);}
async function request(path,method='GET',body,token){
  const response=await fetch(path,{method,cache:'no-store',referrerPolicy:'no-referrer',signal:AbortSignal.timeout(15000),headers:{...(body===undefined?{}:{'Content-Type':'application/json'}),...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  if(!response.ok){let problem;try{problem=await response.json();}catch{};throw Object.assign(new Error('request'),{status:response.status,code:problem?.code});}
  return response.status===204?null:response.json();
}
function render(){
  document.documentElement.lang=language;el('language').setAttribute('aria-label',t('language'));
  el('navigation').replaceChildren(...Object.entries(paths).map(([key,path])=>{const a=link(key,path);if(page===key)a.setAttribute('aria-current','page');return a;}));
  document.querySelector('.brand').href=`/?lang=${language}`;
  const content=el('content');content.replaceChildren();el('error').textContent='';
  if(!configuration){content.append(paragraph('loading'));return;}
  el('mock').hidden=!configuration.mock;el('mock').textContent=t('mock');
  el('footer').textContent=`DONE. · ${configuration.operatorName}`;
  const title=page==='home'?'homeTitle':`${page}Title`;
  document.title=`${t(title)} · DONE.`;content.append(node('h1',t(title)),node('p',t(page==='home'?'homeIntro':`${page}Intro`),{class:'intro'}));
  if(page==='privacy'){
    content.append(node('p',t('effective',[new Intl.DateTimeFormat(language,{dateStyle:'long'}).format(new Date(`${configuration.effectiveDate}T12:00:00Z`))]),{class:'date'}));
    const operator=article('operator','operatorBody');operator.append(facts([['operator',configuration.operatorName],['address',configuration.operatorAddress],['privacyContact',configuration.privacyEmail]]));content.append(operator);
    for(const key of ['data','purpose','sharing','retention','rights','security'])content.append(article(`${key}Heading`,`${key}Body`,key==='retention'?[configuration.backupRetentionDays]:[]));
    const hosting=article('hostingHeading','hostingBody');hosting.append(facts([['hostingRegion',configuration.hostingRegion],['processors',configuration.processors]]));content.append(hosting,article('changesHeading','changesBody'));
  } else if(page==='support'){
    for(const key of ['start','notifications','measure','share','offline'])content.append(article(`${key}Heading`,`${key}Body`));
    const card=article('needHelp','helpBody');card.append(link('contact',paths.contact),node('span',' · '),link('delete',paths.delete));content.append(card);
  } else if(page==='contact'){
    const card=node('article');card.append(facts([['support',configuration.supportEmail],['privacyContact',configuration.privacyEmail],['operator',configuration.operatorName],['address',configuration.operatorAddress]]));
    if(!configuration.mock){const actions=node('div',undefined,{class:'actions'});actions.append(node('a',t('emailSupport'),{class:'button',href:`mailto:${configuration.supportEmail}`}),node('a',t('emailPrivacy'),{class:'button secondary',href:`mailto:${configuration.privacyEmail}`}));card.append(actions);}
    card.append(paragraph(configuration.mock?'mockContact':'contactSafety'));content.append(card);
  } else if(page==='delete') renderDelete(content);
  else{
    const grid=node('div',undefined,{class:'grid'});
    for(const key of ['privacy','support','contact','delete']){const card=node('article',undefined,{class:'card'});card.append(node('h2',t(`${key}Title`)),paragraph(`${key}Intro`),link('open',paths[key]));grid.append(card);}content.append(grid);
  }
}
function renderDelete(content){
  if(deleted){content.append(node('p',t('deleted'),{class:'success'}));return;}
  content.append(article('deleteScope','deleteScopeBody'));
  if(!authentication){
    const card=node('article'),form=node('form');card.append(node('h2',t('signIn')));
    for(const[type,label]of [['email','email'],['password','password']]){const id=`login-${type}`;form.append(node('label',t(label),{for:id}),node('input',undefined,{id,type,name:type,required:'',autocomplete:type==='email'?'username':'current-password',...(type==='password'?{maxlength:'64'}:{maxlength:'254'})}));}
    const submit=node('button',t('signIn'),{type:'submit'});submit.disabled=busy;form.append(submit);form.addEventListener('submit',async event=>{
      event.preventDefault();if(busy)return;const email=form.elements.email.value,secret=form.elements.password.value;busy=true;submit.disabled=true;el('error').textContent='';
      try{authentication=await request('/api/v1/auth/login','POST',{email,password:secret});password=secret;}
      catch(e){error(e.status===401?'credentialsError':e.status===429?'rateError':'networkError');}
      finally{busy=false;if(authentication)render();else submit.disabled=false;}
    });card.append(form);
    if(configuration.appleWebEnabled)card.append(button('apple',appleLogin,'apple'));
    else card.append(node('p',t('appleUnavailable'),{class:'muted status'}));
    content.append(card);
  }else{
    const card=node('article');card.append(node('h2',t('confirmHeading')),facts([['account',authentication.user.email??authentication.user.displayName]]));
    const checkbox=node('input',undefined,{type:'checkbox',id:'confirm-delete'}),label=node('label',undefined,{class:'consent',for:'confirm-delete'});label.append(checkbox,node('span',t('confirmation')));card.append(label);
    const actions=node('div',undefined,{class:'actions'}),remove=button('deleteButton',async()=>{
      if(busy||!checkbox.checked)return;busy=true;remove.disabled=true;el('error').textContent='';
      try{await request('/api/v1/me','DELETE',password?{password}:undefined,authentication.accessToken);authentication=null;password='';deleted=true;render();}
      catch(e){if(e.status===401||e.code==='recent_login_required'){authentication=null;password='';render();error('loginAgain');}else error(e.code==='apple_unavailable'?'appleError':'networkError');}
      finally{busy=false;if(deleted)render();else if(authentication)remove.disabled=!checkbox.checked;}
    },'danger');remove.disabled=true;checkbox.addEventListener('change',()=>remove.disabled=busy||!checkbox.checked);
    actions.append(remove,button('export',async()=>{
      if(busy)return;busy=true;try{const value=await request('/api/v1/me/export','GET',undefined,authentication.accessToken);const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=node('a',undefined,{href:url,download:'done-export.json'});a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}catch{error('networkError');}finally{busy=false;}
    },'secondary'),button('signOut',async()=>{const token=authentication.accessToken;authentication=null;password='';try{await request('/api/v1/auth/logout','POST',undefined,token);}catch{}render();},'secondary'));
    card.append(actions);content.append(card);
  }
}
let sdk;
async function appleLogin(){
  if(busy)return;busy=true;el('error').textContent='';
  try{
    if(!sdk)sdk=new Promise((resolve,reject)=>{const script=document.createElement('script');script.src='https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js';script.onload=resolve;script.onerror=()=>{sdk=null;reject(new Error('sdk'));};document.head.append(script);});
    await sdk;const challenge=await request('/api/v1/auth/apple/challenge','POST');
    AppleID.auth.init({clientId:configuration.appleWebClientId,scope:'name email',redirectURI:configuration.appleWebRedirectURI,state:challenge.challengeId,nonce:challenge.nonce,usePopup:true});
    const result=await AppleID.auth.signIn();if(result.authorization?.state!==challenge.challengeId)throw new Error('state');
    authentication=await request('/api/v1/auth/apple/delete-login','POST',{challengeId:challenge.challengeId,identityToken:result.authorization.id_token,authorizationCode:result.authorization.code,clientId:configuration.appleWebClientId});password='';render();
  }catch(e){error(e.error==='popup_closed_by_user'?'canceled':e.status===401?'credentialsError':'appleError');}
  finally{busy=false;if(authentication)render();}
}
for(const[value,name]of Object.entries(languageNames))el('language').append(node('option',name,{value}));el('language').value=language;
el('language').addEventListener('change',()=>{language=el('language').value;const url=new URL(location.href);url.searchParams.set('lang',language);history.replaceState(null,'',url);render();});
window.addEventListener('pagehide',()=>{authentication=null;password='';});
window.addEventListener('pageshow',event=>{if(event.persisted)render();});
render();try{configuration=await request('/api/v1/public-config');render();}catch{error('networkError');}
