#!/usr/bin/env python3
"""Check resolved third-party Maven coordinates against OSV; never uploads code or secrets.
First run: ./mvnw dependency:tree -DoutputType=json -DoutputFile=target/dependencies.json
Exit 0 = no matching advisories, 1 = advisories found, 2 = unavailable/incomplete scan.
"""
import json, sys, urllib.request
from pathlib import Path
packages=set()
def walk(node):
    for child in node.get('children',[]):
        if child.get('scope') in ('compile','runtime'):
            packages.add((child['groupId']+':'+child['artifactId'],child['version']))
            walk(child)
try:
    walk(json.loads(Path('target/dependencies.json').read_text()))
    ordered=sorted(packages)
    if not ordered: raise ValueError('No runtime dependencies found')
    results=[]
    for start in range(0,len(ordered),100):
        batch=ordered[start:start+100]
        payload={'queries':[{'package':{'ecosystem':'Maven','name':name},'version':version} for name,version in batch]}
        request=urllib.request.Request('https://api.osv.dev/v1/querybatch',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=30) as response: data=json.load(response)
        if len(data.get('results',[]))!=len(batch): raise ValueError('Incomplete OSV response')
        for package,result in zip(batch,data['results']):
            if result.get('next_page_token'): raise ValueError('OSV pagination requires a full per-package scan')
            if result.get('vulns'): results.append({'package':package[0],'version':package[1],'advisories':[v['id'] for v in result['vulns']]})
    Path('target/security-audit.json').write_text(json.dumps({'runtimePackages':len(ordered),'findings':results},indent=2)+'\n')
    for result in results: print(result['package'],result['version'],', '.join(result['advisories']))
    print(f'OSV checked {len(ordered)} runtime Maven packages; {len(results)} packages with advisories. This is not a penetration test or a guarantee of safety.')
    sys.exit(1 if results else 0)
except (OSError,ValueError,KeyError) as error:
    print('Dependency audit unavailable/incomplete:',type(error).__name__,file=sys.stderr)
    sys.exit(2)
