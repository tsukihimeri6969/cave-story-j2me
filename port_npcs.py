import re,glob,sys,os
SRC=sys.argv[1]; OUT=sys.argv[2]; WANT=[int(x) for x in sys.argv[3].split(',')]
# ---- constants
consts={}
for h in ['NpChar.h','Caret.h','CommonDefines.h','Draw.h']:
    t=open(os.path.join(SRC,h),encoding='utf8',errors='replace').read()
    for m in re.finditer(r'enum[^{;]*\{(.*?)\}',t,re.S):
        v=-1
        for line in m.group(1).split('\n'):
            line=line.split('//')[0].strip().rstrip(',')
            if not line: continue
            mm=re.match(r'(\w+)\s*(?:=\s*(.+))?$',line)
            if not mm: continue
            if mm.group(2):
                e=mm.group(2).strip()
                e=re.sub(r'1<<(\d+)',lambda x:str(1<<int(x.group(1))),e)
                try: v=int(eval(e,{}, consts))
                except Exception: continue
            else: v+=1
            consts[mm.group(1)]=v
consts.update({'FLASH_MODE_FLASH':0,'FLASH_MODE_EXPLOSION':1,'TRUE':'true','FALSE':'false','NULL':'-1','SOUND_MODE_PLAY':0,'SOUND_MODE_PLAY_LOOP':-1,'SOUND_MODE_STOP':0})
# ---- functions
funcs={}
for f in sorted(glob.glob(os.path.join(SRC,'NpcAct*.cpp'))):
    t=open(f,encoding='utf8',errors='replace').read()
    for m in re.finditer(r'((?://[^\n]*\n)*)^void ActNpc(\d+)\(NPCHAR \*npc\)\n\{\n(.*?)^\}',t,re.S|re.M):
        c=m.group(1).strip().split('\n')[-1].replace('//','').strip() if m.group(1) else ''
        funcs[int(m.group(2))]=(c,m.group(3))
FIELD={'act_no':'npcState','act_wait':'npcWait','ani_no':'npcAniNo','ani_wait':'npcAniWait','xm':'npcVx','ym':'npcVy','x':'npcX','y':'npcY',
 'direct':'npcDir','flag':'npcCol','tgt_x':'npcTx','tgt_y':'npcTy','count1':'npcC1','count2':'npcC2','bits':'npcBits','damage':'npcDmg',
 'life':'npcHp','shock':'npcShock','code_char':'npcType','exp':'npcExp','code_event':'npcEvent','code_flag':'npcFlag'}
def split_args(s):
    out=[];d=0;cur=''
    for ch in s:
        if ch in '([': d+=1
        if ch in ')]': d-=1
        if ch==',' and d==0: out.append(cur.strip());cur=''
        else: cur+=ch
    out.append(cur.strip()); return out
def call_rewrite(body,name,fn):
    res='';pos=0
    while True:
        m=re.compile(r'\b'+name+r'\s*\(').search(body,pos)
        if not m: res+=body[pos:];break
        res+=body[pos:m.start()]
        i=m.end();d=1
        while d:
            if body[i]=='(':d+=1
            elif body[i]==')':d-=1
            i+=1
        args=split_args(body[m.end():i-1]); res+=fn(args); pos=i
    return res
warn=[]
def conv(n,body):
    b=body
    b=re.sub(r'^\tRECT \w+\[\d*\]\s*=\s*\{.*?^\t\};\n?','',b,flags=re.S|re.M)
    b=re.sub(r'(\+\+)?npc->rect\.\w+\s*(=|\+=)[^;]*;',';',b)
    b=re.sub(r'npc->rect\s*=[^;]*;',';',b)
    b=re.sub(r'\+\+npc->rect\.\w+\s*;',';',b)
    b=b.replace('(npc->rect.bottom - npc->rect.top)','24')
    b=re.sub(r'npc->cond\s*=\s*0\s*;','npcAlive[N] = false;',b)
    b=re.sub(r'npc->cond\s*\|=[^;]*;',';',b)
    b=re.sub(r'npc->surf\s*=\s*(\w+)\s*;',lambda m:'npcSurf[N] = '+str(consts.get(m.group(1),'?'+m.group(1)))+'; ensureSurf(npcSurf[N]);',b)
    b=re.sub(r'gMC\.cond\s*\|=\s*2\s*;','playerHidden = true;',b)
    b=re.sub(r'gMC\.cond\s*&=\s*~2\s*;','playerHidden = false;',b)
    b=b.replace('gMC.equip & EQUIP_MIMIGA_MASK','false').replace('gMC.ani_no % 2','false')
    b=re.sub(r'^\tRECT \w+\s*=\s*\{[^}]*\};\n?','',b,flags=re.M)
    b=re.sub(r'npc->(hit|view)\.(front|top|back|bottom)\s*=\s*([^;]*);',lambda m:('npcHitI' if m.group(1)=='hit' else 'npcViewI')+'[N * 4 + '+str({'front':0,'top':1,'back':2,'bottom':3}[m.group(2)])+'] = ('+m.group(3)+') >> 9;',b)
    b=re.sub(r'npc->(hit|view)\.(front|top|back|bottom)',lambda m:'('+('npcHitI' if m.group(1)=='hit' else 'npcViewI')+'[N * 4 + '+str({'front':0,'top':1,'back':2,'bottom':3}[m.group(2)])+'] << 9)',b)
    b=re.sub(r'gMC\.direct\s*=',' dir =',b)
    b=b.replace('gMC.xm','vx').replace('gMC.ym','vy')
    b=re.sub(r'unsigned char (\w+)',r'int \1',b)
    b=re.sub(r'\bunsigned\s+','',b)
    b=b.replace('BOOL ','boolean ')
    b=re.sub(r'for \(n = 0; n < NPC_MAX; \+\+n\)\s*if \(gNPC\[n\]\.code_event == (\d+)\)\s*break;',r'n = findEvent(\1);',b)
    b=re.sub(r'if \(n == NPC_MAX\)','if (n < 0)',b)
    b=re.sub(r'npc->pNpc\s*=\s*&gNPC\[n\]\s*;','npcPar[N] = n;',b)
    # conditions on flag/bits
    b=re.sub(r'(npc->(?:flag|bits))\s*&\s*(~?\w+)(?!\s*=)',lambda m:'(('+m.group(1)+' & '+m.group(2)+') != 0)',b)
    b=re.sub(r'(gMC\.flag)\s*&\s*(\w+)',lambda m:'((qFlag() & '+m.group(2)+') != 0)',b)
    b=re.sub(r'if \(npc->shock\)','if (npc->shock != 0)',b)
    b=re.sub(r'if \(!npc->shock\)','if (npc->shock == 0)',b)
    # parent
    b=re.sub(r'npc->pNpc->(x|y)\b',lambda m:'npcX[par(N)]' if m.group(1)=='x' else 'npcY[par(N)]',b)
    b=re.sub(r'npc->pNpc->(\w+)',lambda m:FIELD.get(m.group(1),'?'+m.group(1))+'[par(N)]',b)
    b=re.sub(r'npc->pNpc\b','par(N)',b)
    # hit/view
    b=re.sub(r'npc->(\w+)',lambda m:(FIELD[m.group(1)]+'[N]') if m.group(1) in FIELD else ('npc?'+m.group(1)),b)
    # player
    b=b.replace('gMC.x','px').replace('gMC.y','py').replace('gMC.xm','vx').replace('gMC.ym','vy')
    b=b.replace('gMC.direct','quoteDir()')
    b=re.sub(r'\bRandom\(','rnd(',b)
    b=re.sub(r'\bPlaySoundObject\(([^;]*)\);',lambda m:(lambda a:('sfxLoop(' if len(a)>1 and a[1].strip()=='SOUND_MODE_PLAY_LOOP' else ('sfxStop(' if len(a)>1 and a[1].strip()=='SOUND_MODE_STOP' else 'sfx('))+a[0]+');')(split_args(m.group(1))),b)
    def setnp(a):
        a=[x.replace('NULL','-1') for x in a]
        p=a[6]
        p='N' if p=='npc' else ('par(N)' if p=='par(N)' else p)
        return 'setNpc('+', '.join(a[:6])+', '+p+')'
    b=call_rewrite(b,'SetNpChar',setnp)
    b=re.sub(r'\bSetQuake2?\(','quake(',b)
    b=re.sub(r'\bSetCaret\(','caret(',b)
    b=re.sub(r'\bSetDestroyNpCharUp\(','destroyFxUp(',b)
    b=re.sub(r'\bSetDestroyNpChar\(','destroyFx(',b)
    b=re.sub(r'\bSetFlash\(','flash(',b)
    b=re.sub(r'\bGetSin\(','gsin(',b); b=re.sub(r'\bGetCos\(','gcos(',b); b=re.sub(r'\bGetArktan\(','garktan(',b)
    b=re.sub(r'\bDamageMyChar\(','damageMyChar(',b)
    b=re.sub(r'\bChangeMapParts\(','changeMapPart(',b)
    b=re.sub(r'\bDeleteNpCharCode\(','deleteNpcCode(',b)
    b=re.sub(r'\bGetNPCFlag\(([^)]*)\)',r'getFlag(\1)',b)
    b=re.sub(r'\bVanishNpChar\(npc\)','vanishFx(N)',b)
    b=re.sub(r'\bnpc->cond\s*=\s*0\s*;','npcAlive[N] = false;',b)
    b=re.sub(r'\bSetExpObjects\(','expFx(',b)
    b=re.sub(r'\b(NPC_\w+|CARET_\w+|DIR_\w+|SOUND_MODE_\w+|TRUE|FALSE)\b',lambda m:str(consts.get(m.group(1),'?'+m.group(1))),b)
    b=re.sub(r'\(short\)|\(unsigned char\)|\(unsigned short\)','',b)
    b=re.sub(r'% 2\)',r'% 2 != 0)',b)
    b=b.replace('if (0)','if (false)')
    b=b.replace('FLASH_MODE_FLASH','0')
    b=re.sub(r'if \(((?:--|\+\+)?npc\w+\[N\])\)',r'if (\1 != 0)',b)
    b=re.sub(r'if \((npc\w+\[N\]) &&',r'if ((\1 != 0) &&',b)
    b=re.sub(r'if \(!(npc\w+\[N\])\)',r'if (\1 == 0)',b)
    b=b.replace('&& npcWait[N] / 2 % 2 != 0)','&& (npcWait[N] / 2 % 2 != 0))')
    return b
out=['    // ==== generated from CSE2 ====']
disp=[]
for n in WANT:
    if n not in funcs: warn.append('missing %d'%n); continue
    c,b=funcs[n]
    jb=conv(n,b)
    for q in set(re.findall(r'[\w?]*\?\w+',jb)): warn.append('%d: unresolved %s'%(n,q))
    for q in set(re.findall(r'\b(gNPC|gMC\.\w+|GetNpCharAlive|SetValueView|SetBullet\w*|Pos\w*)\b',jb)): warn.append('%d: uses %s'%(n,q))
    out.append('    // %d %s'%(n,c))
    out.append('    private void act%03d(int N) {'%n)
    out.append(jb.rstrip())
    out.append('    }\n')
    disp.append(n)
out.append('    private boolean isPorted(int t) {\n        switch (t) {')
for n in disp: out.append('            case %d:'%n)
out.append('                return true;\n            default: return false;\n        }\n    }\n')
out.append('    private boolean actNpcPort(int i, int t) {\n        switch (t) {')
for n in disp: out.append('            case %d: act%03d(i); return true;'%(n,n))
out.append('            default: return false;\n        }\n    }')
open(OUT,'w',encoding='utf8').write('\n'.join(out)+'\n')
print('\n'.join(sorted(set(warn))))
