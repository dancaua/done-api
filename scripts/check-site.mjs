#!/usr/bin/env node
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
const root=resolve(import.meta.dirname,'..');
const site=resolve(root,'src/main/resources/static/site');
const {copy,languageNames}=await import(pathToFileURL(resolve(site,'copy.js')));
const langs=['en','ro','es','it','fr','de','pl','hi','ja'];
assert.deepEqual(Object.keys(copy).sort(),[...langs].sort());
for(const lang of langs){
  assert.deepEqual(Object.keys(copy[lang]).sort(),Object.keys(copy.en).sort());
  assert(languageNames[lang]);
  for(const [key,text] of Object.entries(copy[lang])){
    assert.equal(typeof text,'string');assert(text.trim(),`${lang}:${key} is blank`);
    assert.deepEqual([...text.matchAll(/\{\d+\}/g)].map(x=>x[0]).sort(),[...copy.en[key].matchAll(/\{\d+\}/g)].map(x=>x[0]).sort(),`${lang}:${key} placeholders`);
  }
}
for(const file of ['site/landing-copy.js','site/landing.js','site/copy.js','site/site.js','site/recovery.js','site/recovery-copy.js','share.js','localizations.js']){
 const result=spawnSync(process.execPath,['--check',resolve(root,'src/main/resources/static',file)],{encoding:'utf8'});
 assert.equal(result.status,0,result.stderr);
}
const script=await readFile(resolve(site,'site.js'),'utf8');
assert(!script.includes('localStorage')&&!script.includes('sessionStorage'),'Auth credentials must stay in memory');
console.log(`Public pages: ${langs.length} complete catalogs × ${Object.keys(copy.en).length} keys; JavaScript syntax verified.`);

const {copy:recovery}=await import(pathToFileURL(resolve(site,'recovery-copy.js')));
assert.deepEqual(Object.keys(recovery).sort(),[...langs].sort());
for(const lang of langs){assert.deepEqual(Object.keys(recovery[lang]).sort(),Object.keys(recovery.en).sort());for(const value of Object.values(recovery[lang]))assert.equal(typeof value,'string');}
console.log(`Recovery pages: ${langs.length} complete catalogs × ${Object.keys(recovery.en).length} keys.`);

const {copy:landing}=await import(pathToFileURL(resolve(site,'landing-copy.js')));
assert.deepEqual(Object.keys(landing).sort(),[...langs].sort());
const html=await readFile(resolve(site,'landing.html'),'utf8');
const lookups=[...html.matchAll(/data-(?:copy|alt|label)="([^"]+)"/g)].map(match=>match[1]);
for(const lang of langs){
 assert.deepEqual(Object.keys(landing[lang]).sort(),Object.keys(landing.en).sort());
 for(const [key,value]of Object.entries(landing[lang]))assert(typeof value==='string'&&value.trim(),`${lang}:${key} blank landing text`);
 for(const key of lookups)assert(landing[lang][key],`${lang}:${key} missing landing lookup`);
}
assert(html.includes('https://apps.apple.com/app/id6820980773'));
console.log(`Marketing landing: ${langs.length} complete catalogs × ${Object.keys(landing.en).length} keys; every HTML lookup resolves.`);

const {catalogs:shared,languageNames:sharedNames}=await import(pathToFileURL(resolve(root,'src/main/resources/static/localizations.js')));
assert.deepEqual(Object.keys(shared).sort(),[...langs].sort());
for(const lang of langs){
 assert(sharedNames[lang]);
 assert.deepEqual(Object.keys(shared[lang]).sort(),Object.keys(shared.en).sort());
 for(const [key,value]of Object.entries(shared[lang])){
  assert(typeof value==='string'&&value.trim(),`${lang}:${key} blank shared status text`);
  assert.deepEqual([...value.matchAll(/\{\d+\}/g)].map(x=>x[0]).sort(),[...shared.en[key].matchAll(/\{\d+\}/g)].map(x=>x[0]).sort(),`${lang}:${key} shared status placeholders`);
 }
}
console.log(`Shared status: ${langs.length} complete catalogs × ${Object.keys(shared.en).length} keys.`);
