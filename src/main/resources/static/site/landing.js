import {copy,languageNames} from './landing-copy.js';
const parameters=new URLSearchParams(location.search);
let language=parameters.get('lang')??navigator.languages?.map(value=>value.split('-')[0]).find(value=>Object.hasOwn(copy,value))??'en';
if(!Object.hasOwn(copy,language))language='en';
const appearance=parameters.get('appearance');
if(['light','dark'].includes(appearance))document.documentElement.dataset.appearance=appearance;
const select=document.getElementById('language');
function render(){
 const strings=copy[language];
 document.documentElement.lang=language;
 document.title=strings.title;
 select.value=language;
 select.setAttribute('aria-label',strings.language);
 for(const element of document.querySelectorAll('[data-copy]'))element.textContent=strings[element.dataset.copy];
 for(const element of document.querySelectorAll('[data-alt]'))element.alt=strings[element.dataset.alt];
 for(const element of document.querySelectorAll('[data-label]'))element.setAttribute('aria-label',strings[element.dataset.label]);
 const urlParameters=new URLSearchParams({lang:language,...(['light','dark'].includes(appearance)?{appearance}:{})});
 for(const link of document.querySelectorAll('[data-site-path]'))link.href=link.dataset.sitePath+'?'+urlParameters;
 document.querySelector('meta[name="description"]').content=strings.description;
 document.querySelector('meta[property="og:title"]').content=strings.title;
 document.querySelector('meta[property="og:description"]').content=strings.description;
 for(const note of document.querySelectorAll('.release-note'))note.hidden=document.body.dataset.release==='live';
 for(const link of document.querySelectorAll('.brand'))link.href='/?'+urlParameters;
}
select.addEventListener('change',()=>{
 if(!Object.hasOwn(languageNames,select.value))return;
 language=select.value;
 const url=new URL(location.href);url.searchParams.set('lang',language);
 history.replaceState(null,'',url);render();
});
render();
