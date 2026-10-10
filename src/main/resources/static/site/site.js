import {copy as recoveryCopy} from './recovery-copy.js';
import { copy, languageNames } from './copy.js';

const el=id=>document.getElementById(id);
const appearance=new URL(location.href).searchParams.get('appearance');
if(['light','dark'].includes(appearance)) document.documentElement.dataset.appearance=appearance;
const pageURL=path=>`${path}?${new URLSearchParams({lang:language,...(['light','dark'].includes(appearance)?{appearance}:{})})}`;
const iconPaths={
 terms:'M6 3h9l3 3v15H6V3Z M14 3v5h4 M9 12h6 M9 16h6',
 privacy:'M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6l-8-3Z M9 12l2 2 4-4',
 support:'M9.5 9a2.5 2.5 0 1 1 4 2c-1 .6-1.5 1-1.5 2 M12 16h.01 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
 contact:'M4 5h16v14H4V5Z M4 6l8 6 8-6',
 delete:'M4 7h16 M9 7V4h6v3 M6 7l1 14h10l1-14 M10 11v6 M14 11v6',
 home:'M3 11l9-8 9 8 M5 10v11h14V10 M9 21v-7h6v7',
 account:'M8 10V7a4 4 0 0 1 8 0v3 M5 10h14v11H5V10Z M12 14v3'
};
function icon(name){
 const wrapper=node('span',undefined,{class:'icon','aria-hidden':'true'});
 const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
 for(const[k,v]of Object.entries({viewBox:'0 0 24 24',fill:'none',stroke:'currentColor','stroke-width':'1.6','stroke-linecap':'round','stroke-linejoin':'round'}))svg.setAttribute(k,v);
 const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',iconPaths[name]??iconPaths.home);svg.append(path);wrapper.append(svg);return wrapper;
}
function heading(title,symbol){const row=node('div',undefined,{class:'section-title'});row.append(icon(symbol),node('h2',t(title)));return row;}
let language=new URL(location.href).searchParams.get('lang') ?? navigator.languages?.map(l=>l.slice(0,2)).find(l=>copy[l]) ?? 'en';
if(!copy[language]) language='en';
let configuration, authentication, password='', busy=false, deleted=false;
const t=(key,args=[])=>copy[language][key].replace(/\{(\d+)\}/g,(_,n)=>String(args[Number(n)]??`{${n}}`));
const paths={privacy:'/privacy',support:'/support',contact:'/contact',delete:'/delete-account',terms:'/terms'};
const page=['/terms','/disclaimer'].includes(location.pathname)?'terms':location.pathname.includes('privacy')?'privacy':location.pathname.includes('support')?'support':location.pathname.includes('contact')?'contact':location.pathname.includes('delet')?'delete':'home';
function node(tag,text,attributes={}){const result=document.createElement(tag);if(text!==undefined)result.textContent=text;for(const[key,value]of Object.entries(attributes))result.setAttribute(key,value);return result;}
const paragraph=(key,args)=>node('p',t(key,args));
function article(title,key,args){const result=node('article');result.append(node('h2',t(title)),paragraph(key,args));return result;}
function link(key,path){return node('a',t(key),{href:pageURL(path)});}
function button(key,action,classes=''){const result=node('button',t(key),{type:'button',class:classes});result.disabled=busy;result.addEventListener('click',action);return result;}
function facts(values){const dl=node('dl');for(const[key,value]of values){const row=node('div',undefined,{class:'fact'});row.append(node('dt',t(key)),node('dd',value));dl.append(row);}return dl;}
function error(key){el('error').textContent=t(key);}
async function request(path,method='GET',body,token){
  const response=await fetch(path,{method,cache:'no-store',referrerPolicy:'no-referrer',signal:AbortSignal.timeout(15000),headers:{...(body===undefined?{}:{'Content-Type':'application/json'}),...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  if(!response.ok){let problem;try{problem=await response.json();}catch{};throw Object.assign(new Error('request'),{status:response.status,code:problem?.code});}
  return response.status===204?null:response.json();
}
function render(){
  document.documentElement.lang=language;el('language').setAttribute('aria-label',t('language'));
  el('navigation').replaceChildren(...Object.entries(paths).map(([key,path])=>{const a=link(key,path);if(page===key)a.setAttribute('aria-current','page');return a;}));
  document.querySelector('.brand').href=pageURL('/');
  const content=el('content');content.replaceChildren();el('error').textContent='';
  if(!configuration){content.append(paragraph('loading'));return;}
  el('mock').hidden=!configuration.mock;el('mock').textContent=t('mock');
  el('footer').textContent=`DONE. · ${configuration.operatorName}`;
  const title=page==='home'?'homeTitle':`${page}Title`;
  document.title=`${t(title)} · DONE.`;const hero=node('div',undefined,{class:'hero'});hero.append(icon(page),node('h1',t(title)),node('p',t(page==='home'?'homeIntro':`${page}Intro`),{class:'intro'}));content.append(hero);
  if(page==='terms'){
    for(const key of ['scope','safety','alerts','sharing','rights'])content.append(article(`legal.${key}.title`,`legal.${key}.body`));
    const operator=article('operator','operatorBody');operator.append(facts([['operator',configuration.operatorName],['address',configuration.operatorAddress],['privacyContact',configuration.privacyEmail]]));content.append(operator);
  } else if(page==='privacy'){
    hero.append(node('p',t('effective',[new Intl.DateTimeFormat(language,{dateStyle:'long'}).format(new Date(`${configuration.effectiveDate}T12:00:00Z`))]),{class:'date'}));
    const operator=article('operator','operatorBody');operator.append(facts([['operator',configuration.operatorName],['address',configuration.operatorAddress],['privacyContact',configuration.privacyEmail]]));content.append(operator);
    for(const key of ['data','purpose','sharing','retention','rights','security'])content.append(article(`${key}Heading`,`${key}Body`,key==='retention'?[configuration.backupRetentionDays]:[]));
    content.append(article('legal.scope.title','legal.scope.body'),article('legal.applePrivacy.title','legal.applePrivacy'));
    const hosting=article('hostingHeading','hostingBody');hosting.append(facts([['hostingRegion',configuration.hostingRegion],['processors',configuration.processors]]));content.append(hosting,article('changesHeading','changesBody'));
  } else if(page==='support'){
    content.append(article('legal.scope.title','legal.scope.body'),article('legal.safety.title','legal.safety.body'));
    const faq=node('section',undefined,{class:'faq','aria-label':t('support')});for(const key of ['start','notifications','measure','share','offline']){const item=node('details');item.append(node('summary',t(`${key}Heading`)),paragraph(`${key}Body`));faq.append(item);}content.append(faq);
    const card=article('needHelp','helpBody');card.className='help-card';const actions=node('div',undefined,{class:'actions'});const contact=link('contact',paths.contact),remove=link('delete',paths.delete);contact.className='button';remove.className='button secondary';actions.append(contact,remove);card.append(actions);content.append(card);
  } else if(page==='contact'){
    const grid=node('div',undefined,{class:'contact-grid'});
    for(const[key,email,action,symbol]of [['support',configuration.supportEmail,'emailSupport','support'],['privacyContact',configuration.privacyEmail,'emailPrivacy','privacy']]){
      const card=node('article');card.append(heading(key,symbol),node('p',email,{class:'email'}));
      if(!configuration.mock)card.append(node('a',t(action),{class:'button secondary',href:`mailto:${email}`}));
      grid.append(card);
    }
    const details=node('article');details.append(facts([['operator',configuration.operatorName],['address',configuration.operatorAddress]]));
    const note=node('p',t(configuration.mock?'mockContact':'contactSafety'),{class:'muted status'});details.append(note);content.append(grid,details);
  } else if(page==='delete') renderDelete(content);
  else{
    const grid=node('div',undefined,{class:'grid'});
    for(const key of ['privacy','support','contact','delete']){const card=node('article',undefined,{class:'card'});card.append(icon(key),node('h2',t(`${key}Title`)),paragraph(`${key}Intro`),link('open',paths[key]));grid.append(card);}content.append(grid);
  }
}
function renderDelete(content){
  if(deleted){content.append(node('p',t('deleted'),{class:'success'}));return;}
  const layout=node('div',undefined,{class:'delete-layout'}),scope=article('deleteScope','deleteScopeBody');scope.className='scope';scope.replaceChildren(heading('deleteScope','delete'),paragraph('deleteScopeBody'));layout.append(scope);content.append(layout);content=layout;
  if(!authentication){
    const card=node('article',undefined,{class:'login-card'}),form=node('form');card.append(heading('signIn','account'));
    for(const[type,label]of [['email','email'],['password','password']]){const id=`login-${type}`;form.append(node('label',t(label),{for:id}),node('input',undefined,{id,type,name:type,required:'',autocomplete:type==='email'?'username':'current-password',...(type==='password'?{maxlength:'64'}:{maxlength:'254'})}));}
    const submit=node('button',t('signIn'),{type:'submit'});submit.disabled=busy;form.append(submit);form.addEventListener('submit',async event=>{
      event.preventDefault();if(busy)return;const email=form.elements.email.value,secret=form.elements.password.value;busy=true;submit.disabled=true;el('error').textContent='';
      try{authentication=await request('/api/v1/auth/login','POST',{email,password:secret});password=secret;}
      catch(e){error(e.status===401?'credentialsError':e.status===429?'rateError':'networkError');}
      finally{busy=false;if(authentication)render();else submit.disabled=false;}
    });card.append(form,node('a',recoveryCopy[language].forgot,{href:pageURL('/forgot-password')}));
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
