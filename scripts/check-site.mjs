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
for(const file of ['site/copy.js','site/site.js','share.js','localizations.js']){
 const result=spawnSync(process.execPath,['--check',resolve(root,'src/main/resources/static',file)],{encoding:'utf8'});
 assert.equal(result.status,0,result.stderr);
}
const script=await readFile(resolve(site,'site.js'),'utf8');
assert(!script.includes('localStorage')&&!script.includes('sessionStorage'),'Auth credentials must stay in memory');
console.log(`Public pages: ${langs.length} complete catalogs × ${Object.keys(copy.en).length} keys; JavaScript syntax verified.`);
