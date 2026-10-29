"""Explicit, bounded Windows-to-WSL Hermes discovery and profile operations.

This module never accesses a distro during passive discovery. Guest commands are
only issued after a distro has been selected by the user.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
import uuid
import sys
from dataclasses import dataclass, field
from typing import Any, Callable


_PROFILE_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
_HEX = re.compile(r"^[0-9a-f]{64}$")
_API_KEYS = ("API_SERVER_ENABLED", "API_SERVER_HOST", "API_SERVER_PORT", "API_SERVER_KEY")
_MAX_OUTPUT = 256 * 1024
_LIST_TIMEOUT = 4.0
_GUEST_TIMEOUT = 8.0
_START_TIMEOUT = 30.0


class WslHermesError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class WslDistro:
    id: str
    name: str
    state: str
    version: str = "unknown"


@dataclass(frozen=True)
class WslProfile:
    id: str
    label: str
    api_state: str


@dataclass
class WslOperation:
    operation_id: str
    status: str = "pending"
    phase: str = "queued"
    message: str = "正在等待 WSL 操作。"
    distro_id: str = ""
    profiles: list[dict[str, str]] = field(default_factory=list)
    result: dict[str, Any] = field(default_factory=dict)
    cancel_wait: threading.Event = field(default_factory=threading.Event, repr=False)
    worker: threading.Thread | None = field(default=None, repr=False)
    updated_at: float = field(default_factory=time.time)


_GUEST_BOOTSTRAP = r'''import json,os,re,shutil,subprocess,sys
NAME=re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
def fail(code,msg): print(json.dumps({"ok":False,"code":code,"message":msg})); sys.exit(0)
try:
 req=json.loads(sys.stdin.buffer.read(65536).decode("utf-8")); profile=req.get("profile")
 if not isinstance(profile,str) or not NAME.fullmatch(profile): raise ValueError()
 home=os.path.expanduser("~")
 hermes_candidates=[shutil.which("hermes"),home+"/.local/bin/hermes",home+"/.hermes/bin/hermes",home+"/.hermes/hermes-agent/.hermes/bin/hermes",home+"/hermes-agent/.venv/bin/hermes",home+"/hermes-agent/venv/bin/hermes","/usr/local/bin/hermes","/usr/bin/hermes"]
 hermes=next((os.path.realpath(p) for p in hermes_candidates if p and os.path.isfile(p) and os.access(p,os.X_OK)),None)
 if not hermes: fail("hermes_missing","Hermes was not found in supported WSL command locations.")
 needs_yaml=os.path.isfile(home+"/.hermes/config.yaml") or (profile!="default" and os.path.isfile(home+"/.hermes/profiles/"+profile+"/config.yaml"))
 candidates=[sys.executable,home+"/.hermes/hermes-agent/.hermes/bin/python",home+"/.hermes/hermes-agent/.venv/bin/python",home+"/.hermes/hermes-agent/venv/bin/python",home+"/hermes-agent/.venv/bin/python",home+"/hermes-agent/venv/bin/python"]
 shebang=open(hermes,"rb").readline(512).decode("utf-8","ignore").strip()
 if shebang.startswith("#!"):
  parts=shebang[2:].split()
  if parts:
   candidate=shutil.which(parts[-1]) if parts[0].endswith("/env") else parts[0]
   if candidate: candidates.append(candidate)
 seen=set(); selected=None
 for path in candidates:
  if not path or path in seen or not os.path.isfile(path) or not os.access(path,os.X_OK): continue
  seen.add(path)
  if not needs_yaml:
   selected=path; break
  check=subprocess.run([path,"-c","import yaml"],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=2,check=False)
  if check.returncode==0: selected=path; break
 if not selected: fail("hermes_python_unavailable","The selected Hermes profile has config.yaml, but no supported Hermes Python runtime can safely parse it.")
 print(json.dumps({"ok":True,"python":selected,"hermes":hermes}))
except Exception: fail("hermes_python_unavailable","A supported Hermes Python runtime could not be located safely.")
'''


_GUEST_HELPER = r'''import hashlib,json,os,re,secrets,shutil,stat,subprocess,sys,tempfile,time
MAX=256*1024
API=("API_SERVER_ENABLED","API_SERVER_HOST","API_SERVER_PORT","API_SERVER_KEY")
NAME=re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
def fail(code,msg): print(json.dumps({"ok":False,"code":code,"message":msg})); sys.exit(0)
def profile_home(name):
    if not isinstance(name,str) or not NAME.fullmatch(name): raise ValueError("invalid profile")
    root=os.path.expanduser("~/.hermes")
    st=os.lstat(root)
    if not stat.S_ISDIR(st.st_mode) or stat.S_ISLNK(st.st_mode): raise ValueError("Hermes home is not a safe directory")
    root=os.path.realpath(root)
    if name=="default": return root
    profiles=os.path.join(root,"profiles")
    pst=os.lstat(profiles)
    if not stat.S_ISDIR(pst.st_mode) or stat.S_ISLNK(pst.st_mode) or os.path.realpath(profiles)!=profiles: raise ValueError("Hermes profiles directory is not safe")
    selected=os.path.join(profiles,name)
    sst=os.lstat(selected)
    if not stat.S_ISDIR(sst.st_mode) or stat.S_ISLNK(sst.st_mode) or os.path.realpath(selected)!=selected: raise ValueError("selected profile directory is not safe")
    return selected
def safe_profiles():
    root=os.path.expanduser("~/.hermes")
    st=os.lstat(root)
    if not stat.S_ISDIR(st.st_mode) or stat.S_ISLNK(st.st_mode): raise ValueError("Hermes home is not a safe directory")
    root=os.path.realpath(root); found=["default"]
    directory=os.path.join(root,"profiles")
    try: pst=os.lstat(directory)
    except FileNotFoundError: return found
    if not stat.S_ISDIR(pst.st_mode) or stat.S_ISLNK(pst.st_mode) or os.path.realpath(directory)!=directory: raise ValueError("Hermes profiles directory is not safe")
    names=os.listdir(directory)
    if len(names)>256: raise ValueError("too many Hermes profiles to inspect safely")
    identity=("config.yaml",".env","SOUL.md","profile.yaml","auth.json","state.db")
    for name in sorted(names):
        if not NAME.fullmatch(name): continue
        path=os.path.join(directory,name)
        try:
            pst=os.lstat(path)
            if not stat.S_ISDIR(pst.st_mode) or stat.S_ISLNK(pst.st_mode) or os.path.realpath(path)!=path: continue
            valid=False
            for leaf in identity:
                try:
                    item=os.lstat(os.path.join(path,leaf))
                    if stat.S_ISREG(item.st_mode) and not stat.S_ISLNK(item.st_mode): valid=True; break
                except FileNotFoundError: pass
            if valid: found.append(name)
        except OSError: continue
    return found
def read_file(path):
    try: fd=os.open(path,os.O_RDONLY|getattr(os,"O_NOFOLLOW",0)|getattr(os,"O_CLOEXEC",0))
    except FileNotFoundError: return None
    except OSError: raise ValueError("selected config file is not safely readable")
    try:
        st=os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_size>MAX: raise ValueError("selected config file is not a bounded regular file")
        b=b""
        while len(b)<=MAX:
            c=os.read(fd,min(8192,MAX+1-len(b)))
            if not c: break
            b+=c
        if len(b)>MAX: raise ValueError("selected config file is too large")
        return b
    finally: os.close(fd)
def dotenv(raw):
    out={}; counts={}
    if raw is None: return out
    try: text=raw.decode("utf-8")
    except UnicodeDecodeError: raise ValueError("selected .env is not UTF-8")
    for line in text.splitlines():
        s=line.strip()
        if not s or s.startswith("#"): continue
        if s.startswith("export "): s=s[7:].lstrip()
        m=re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)",s)
        if not m or m.group(1) not in API+('GATEWAY_MULTIPLEX_PROFILES',): continue
        k,v=m.groups(); counts[k]=counts.get(k,0)+1
        if counts[k]>1: raise ValueError("selected .env has duplicate protected settings")
        v=v.strip()
        if v[:1] in ("'",'"'):
            if len(v)<2 or v[-1]!=v[0]: raise ValueError("selected .env has an invalid API_SERVER value")
            v=v[1:-1]
        else:
            if any(x in v for x in ("$(","${","`","\r","\n")): raise ValueError("selected .env API settings must be literal")
            v=v.split(" #",1)[0].strip()
        out[k]=v
    return out
def yaml_api(raw):
    if raw is None: return {},{}
    try:
        import yaml
        obj=yaml.safe_load(raw.decode("utf-8")) or {}
    except Exception: raise ValueError("selected config.yaml API settings cannot be safely inspected")
    if not isinstance(obj,dict): raise ValueError("selected config.yaml has an unsupported shape")
    blocks=[]
    for path in (("gateway","api_server"),("gateway","platforms","api_server"),("platforms","api_server")):
        cur=obj
        for k in path: cur=cur.get(k,{}) if isinstance(cur,dict) else {}
        if cur:
            if not isinstance(cur,dict): raise ValueError("selected config.yaml API settings have an unsupported shape")
            blocks.append(cur)
    if len(blocks)>1: raise ValueError("selected config.yaml has multiple API server blocks")
    vals={};
    for block in blocks:
        for source,target in (("enabled","API_SERVER_ENABLED"),("host","API_SERVER_HOST"),("port","API_SERVER_PORT"),("key","API_SERVER_KEY")):
            if source in block:
                val=block[source]
                if isinstance(val,(dict,list)) or val is None: raise ValueError("selected config.yaml API setting has an unsupported value")
                vals[target]=str(val).lower() if isinstance(val,bool) else str(val)
    gw=obj.get("gateway",{}) if isinstance(obj.get("gateway",{}),dict) else {}
    multiplex=gw.get("multiplex_profiles",obj.get("multiplex_profiles")); standalone=gw.get("standalone")
    if multiplex is not None and type(multiplex) is not bool: raise ValueError("multiplex_profiles must be a YAML boolean")
    if standalone is not None and type(standalone) is not bool: raise ValueError("standalone must be a YAML boolean")
    modes={"multiplex":multiplex,"standalone":standalone}
    return vals,modes
def locate_hermes():
    candidates=[shutil.which("hermes"),os.path.expanduser("~/.local/bin/hermes"),os.path.expanduser("~/.hermes/bin/hermes"),os.path.expanduser("~/.hermes/hermes-agent/.hermes/bin/hermes"),os.path.expanduser("~/hermes-agent/.venv/bin/hermes"),os.path.expanduser("~/hermes-agent/venv/bin/hermes"),"/usr/local/bin/hermes","/usr/bin/hermes"]
    for path in candidates:
        if path and os.path.isfile(path) and os.access(path,os.X_OK): return os.path.realpath(path)
    return None
def gateway_state(home):
    state=None
    try:
        sr=read_file(os.path.join(home,"gateway_state.json"))
        if sr is None: return None
        obj=json.loads(sr.decode("utf-8")); pid=obj.get("pid"); served=obj.get("served_profiles",[])
        if type(pid) is not int or pid<=1 or not isinstance(served,list) or len(served)>256 or any(not isinstance(x,str) or not NAME.fullmatch(x) for x in served): return None
        proc="/proc/"+str(pid)
        st=os.stat(proc)
        if st.st_uid!=os.getuid(): return None
        cmd=open(proc+"/cmdline","rb").read(16384).replace(b"\0",b" ").decode("utf-8","ignore").lower()
        statline=open(proc+"/stat","r",encoding="ascii").read()
        close=statline.rfind(")")
        fields=statline[close+2:].split() if close>=0 else []
        start_ticks=int(fields[19]) if len(fields)>19 else 0
        if "gateway" not in cmd or start_ticks<=0: return None
        state={"pid":pid,"start_ticks":start_ticks,"served_profiles":served}
    except Exception: return None
    return state
def start_gateway(name,mode,identity):
    selected=profile_home(name)
    host_name="default" if mode=="shared" else name
    host=profile_home(host_name)
    exe=locate_hermes()
    if not exe: raise ValueError("Hermes CLI was not found in supported WSL locations")
    live=gateway_state(host)
    def attach_selected():
        if mode!="shared" or name=="default": return None
        result=subprocess.run([exe,"-p",name,"gateway","run"],stdin=subprocess.DEVNULL,
                              stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=12,check=False)
        if result.returncode!=0: raise ValueError("The shared gateway could not attach this profile; no standalone gateway was started")
        return {"started":False,"attached":True}
    if live:
        if host_name==name or name in live.get("served_profiles",[]):
            return {"started":False,"attached":True,"pid":live["pid"]}
        attached=attach_selected(); attached["pid"]=live["pid"]; return attached
    receipt=os.path.join(host,".padnote-wsl-gateway-receipt.json")
    boot_id=open("/proc/sys/kernel/random/boot_id","r",encoding="ascii").read().strip()
    try: raw_receipt=read_file(receipt)
    except Exception: raise ValueError("Gateway start receipt is unreadable; inspect Hermes before retrying")
    if raw_receipt is not None:
        try: prior=json.loads(raw_receipt.decode("utf-8"))
        except Exception: raise ValueError("Gateway start receipt is damaged; inspect Hermes before retrying")
        if not isinstance(prior,dict) or prior.get("schema")!=1 or not isinstance(prior.get("boot_id"),str):
            raise ValueError("Gateway start receipt is not verifiable; inspect Hermes before retrying")
        if prior.get("state")=="starting":
            if prior.get("boot_id")==boot_id:
                raise ValueError("A previous gateway start has an unknown outcome; inspect Hermes before retrying")
            stale=True
        elif prior.get("state")=="started" and type(prior.get("pid")) is int and type(prior.get("start_ticks")) is int:
            stale=False
        else: raise ValueError("Gateway start receipt is not verifiable; inspect Hermes before retrying")
        if stale:
            proc=None
        else:
            proc="/proc/"+str(prior["pid"])
        try:
            if stale: raise FileNotFoundError()
            st=os.stat(proc)
            statline=open(proc+"/stat","r",encoding="ascii").read(); close=statline.rfind(")")
            fields=statline[close+2:].split() if close>=0 else []
            current_ticks=int(fields[19]) if len(fields)>19 else 0
            cmd=open(proc+"/cmdline","rb").read(16384).replace(b"\0",b" ").decode("utf-8","ignore").lower()
            if st.st_uid==os.getuid() and current_ticks==prior["start_ticks"] and "gateway" in cmd:
                if prior.get("identity")==identity:
                    attached=attach_selected()
                    return attached or {"started":False,"attached":True,"pid":prior["pid"]}
                raise ValueError("A different PadNote-managed gateway is already active for this profile")
        except FileNotFoundError: pass
        except ValueError: raise
        except Exception: raise ValueError("Gateway process identity could not be verified; inspect Hermes before retrying")
        # A recorded PID that now exists with another start time is ambiguous.
        if proc and os.path.exists(proc):
            raise ValueError("Gateway process identity changed; inspect Hermes before retrying")
        # A verified old receipt whose process is gone (or belongs to a prior
        # WSL boot) can be removed only while its full bytes still match.
        if read_file(receipt)!=raw_receipt:
            raise ValueError("Gateway start receipt changed; inspect Hermes before retrying")
        os.unlink(receipt)
        dfd=os.open(os.path.dirname(receipt),os.O_RDONLY|getattr(os,"O_DIRECTORY",0)); os.fsync(dfd); os.close(dfd)
    nonce=secrets.token_hex(16)
    payload={"schema":1,"state":"starting","nonce":nonce,"boot_id":boot_id,
             "identity":identity,"owner_profile":host_name,"selected_profile":name}
    directory=os.path.dirname(receipt)
    def save_receipt(value,create=False):
        data=json.dumps(value,separators=(",",":")).encode("utf-8")
        if create:
            fd=os.open(receipt,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,"O_NOFOLLOW",0),0o600)
            with os.fdopen(fd,"wb") as f: f.write(data); f.flush(); os.fsync(f.fileno())
            dfd=os.open(directory,os.O_RDONLY|getattr(os,"O_DIRECTORY",0)); os.fsync(dfd); os.close(dfd)
            return
        observed=read_file(receipt)
        if observed is None or json.loads(observed.decode("utf-8")).get("nonce")!=nonce:
            raise ValueError("gateway start reservation changed")
        fd,tmp=tempfile.mkstemp(prefix=".padnote-gw-",dir=directory)
        try:
            os.fchmod(fd,0o600)
            with os.fdopen(fd,"wb") as f: f.write(data); f.flush(); os.fsync(f.fileno())
            os.replace(tmp,receipt)
            dfd=os.open(directory,os.O_RDONLY|getattr(os,"O_DIRECTORY",0)); os.fsync(dfd); os.close(dfd)
        finally:
            try: os.unlink(tmp)
            except FileNotFoundError: pass
    try: save_receipt(payload,create=True)
    except FileExistsError: raise ValueError("A gateway start reservation appeared; inspect Hermes before retrying")
    command=[exe,"-p",host_name,"gateway","run"]
    try:
        process=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True,close_fds=True)
        statline=open("/proc/"+str(process.pid)+"/stat","r",encoding="ascii").read(); close=statline.rfind(")")
        fields=statline[close+2:].split() if close>=0 else []
        startticks=int(fields[19]) if len(fields)>19 else 0
        if startticks<=0: raise ValueError("gateway process identity unavailable")
        payload.update({"state":"started","pid":process.pid,"start_ticks":startticks})
        save_receipt(payload)
        return {"started":True,"attached":False,"pid":process.pid}
    except Exception:
        # Keep the pre-fork reservation. Retrying could duplicate an uncertain start.
        raise ValueError("Gateway start outcome is uncertain; inspect Hermes before retrying")
def inspect(name):
    home=profile_home(name); envpath=os.path.join(home,".env"); cfgpath=os.path.join(home,"config.yaml")
    envraw=read_file(envpath); cfgr=read_file(cfgpath); env=dotenv(envraw); y,modes=yaml_api(cfgr)
    effective=dict(y); effective.update(env)
    root=os.path.realpath(os.path.expanduser("~/.hermes"))
    default_cfg=read_file(os.path.join(root,"config.yaml"))
    default_env=read_file(os.path.join(root,".env"))
    default_y,default_modes=yaml_api(default_cfg)
    default_values=dict(default_y); default_values.update(dotenv(default_env))
    default_state=gateway_state(root)
    profile_state=gateway_state(home)
    selected_served=bool(default_state and name in default_state["served_profiles"])
    if name=="default": mode="default"
    elif modes.get("standalone") is True and profile_state: mode="standalone"
    elif selected_served: mode="shared"
    elif modes.get("standalone") is True: mode="standalone"
    elif default_modes.get("multiplex") is True or default_values.get("GATEWAY_MULTIPLEX_PROFILES","").lower() in ("1","true","yes","on"): mode="shared"
    elif default_modes.get("multiplex") is False or default_values.get("GATEWAY_MULTIPLEX_PROFILES","").lower() in ("0","false","no","off"): mode="unknown"
    else: mode="unknown"
    exe=locate_hermes()
    raw_enabled=effective.get("API_SERVER_ENABLED","").lower()
    if raw_enabled and raw_enabled not in ("true","false","1","0","yes","no","on","off"):
        raise ValueError("API_SERVER_ENABLED must be a literal boolean")
    enabled=raw_enabled in ("true","1","yes","on")
    key=effective.get("API_SERVER_KEY","")
    host=effective.get("API_SERVER_HOST","127.0.0.1")
    port=effective.get("API_SERVER_PORT","8642")
    if not key: api="missing_key"
    elif not isinstance(key,str) or len(key.encode("utf-8"))>4096 or "\r" in key or "\n" in key: api="invalid_key"
    elif host not in ("127.0.0.1","::1","[::1]"): api="unsafe_host"
    elif not str(port).isdigit() or not 1<=int(port)<=65535: api="invalid_port"
    elif enabled: api="configured"
    else: api="disabled"
    try: env_mode=stat.S_IMODE(os.lstat(envpath).st_mode) if envraw is not None else None
    except OSError: raise ValueError("selected .env metadata changed during inspection")
    try: config_mode=stat.S_IMODE(os.lstat(cfgpath).st_mode) if cfgr is not None else None
    except OSError: raise ValueError("selected config.yaml metadata changed during inspection")
    try: default_env_mode=stat.S_IMODE(os.lstat(os.path.join(root,".env")).st_mode) if default_env is not None else None
    except OSError: raise ValueError("default .env metadata changed during inspection")
    try: default_config_mode=stat.S_IMODE(os.lstat(os.path.join(root,"config.yaml")).st_mode) if default_cfg is not None else None
    except OSError: raise ValueError("default config.yaml metadata changed during inspection")
    actual_gateway=default_state if mode=="shared" and name!="default" else profile_state
    return {"ok":True,"profile":name,"uid":os.getuid(),"home":os.path.realpath(home),"hermes_path":exe,"api_state":api,"effective":{"enabled":enabled,"host":host,"port":int(port) if str(port).isdigit() else None,"key":key},"env_sha256":hashlib.sha256(envraw or b"").hexdigest() if envraw is not None else None,"config_sha256":hashlib.sha256(cfgr or b"").hexdigest() if cfgr is not None else None,"default_env_sha256":hashlib.sha256(default_env or b"").hexdigest() if default_env is not None else None,"default_config_sha256":hashlib.sha256(default_cfg or b"").hexdigest() if default_cfg is not None else None,"env_mode":env_mode,"config_mode":config_mode,"default_env_mode":default_env_mode,"default_config_mode":default_config_mode,"env_exists":envraw is not None,"config_exists":cfgr is not None,"mode":mode,"multiplex_profiles":modes.get("multiplex"),"standalone":modes.get("standalone"),"gateway":actual_gateway,"profiles":safe_profiles(),"python":sys.version_info[:2]}
def write_env(name,expected_env,expected_config,expected_env_mode,expected_config_mode,expected_mode,updates):
    home=profile_home(name)
    path=os.path.join(home,".env"); current=read_file(path); config=read_file(os.path.join(home,"config.yaml"))
    digest=hashlib.sha256(current or b"").hexdigest() if current is not None else None
    config_digest=hashlib.sha256(config or b"").hexdigest() if config is not None else None
    env_mode=stat.S_IMODE(os.lstat(path).st_mode) if current is not None else None
    config_mode=stat.S_IMODE(os.lstat(os.path.join(home,"config.yaml")).st_mode) if config is not None else None
    if digest!=expected_env or config_digest!=expected_config or env_mode!=expected_env_mode or config_mode!=expected_config_mode: return {"ok":False,"code":"stale_config","message":"Profile config changed; inspect it again before applying."}
    if inspect(name).get("mode")!=expected_mode: return {"ok":False,"code":"stale_config","message":"Gateway mode changed; inspect it again before applying."}
    if not isinstance(updates,dict) or not updates or any(k not in API for k in updates): raise ValueError("invalid API update")
    old=dotenv(current); merged=dict(old); merged.update({k:str(v) for k,v in updates.items()})
    lines=(current or b"").decode("utf-8").splitlines(keepends=True) if current is not None else []
    seen=set(); out=[]; newline=b"\r\n" if current and b"\r\n" in current else b"\n"
    for line in lines:
        body=line[:-2] if line.endswith("\r\n") else line[:-1] if line.endswith(("\n","\r")) else line
        ending=line[len(body):]
        s=body.strip(); key=None
        if s.startswith("export "): s=s[7:].lstrip()
        m=re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)",s)
        if m and m.group(1) in updates:
            key=m.group(1)
            if key in seen: raise ValueError("duplicate API setting prevents safe update")
            seen.add(key); out.append((key+"="+json.dumps(merged[key],ensure_ascii=False)).encode("utf-8")+ending.encode("utf-8")); continue
        out.append(line.encode("utf-8"))
    missing=[key for key in updates if key not in seen]
    if missing:
        if out and not out[-1].endswith((b"\n",b"\r")): out[-1]+=newline
        for index,key in enumerate(missing):
            ending=newline if (current or b"\n").endswith((b"\n",b"\r")) or index<len(missing)-1 else b""
            out.append((key+"="+json.dumps(merged[key],ensure_ascii=False)).encode("utf-8")+ending)
    content=b"".join(out)
    if len(content)>MAX: raise ValueError("updated .env would exceed the safe size limit")
    stamp=str(int(time.time()))
    backup=path+".padnote-backup-"+stamp+"-"+secrets.token_hex(4)
    if current is not None:
        bfd=os.open(backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,"O_NOFOLLOW",0),0o600)
        try:
            with os.fdopen(bfd,"wb") as f: f.write(current); f.flush(); os.fsync(f.fileno())
        except BaseException:
            try: os.unlink(backup)
            except OSError: pass
            raise
    fd,tmp=tempfile.mkstemp(prefix=".padnote-env-",dir=home)
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,"wb") as f: f.write(content); f.flush(); os.fsync(f.fileno())
        # CAS once more immediately before the atomic replace.
        latest=read_file(path); latest_hash=hashlib.sha256(latest or b"").hexdigest() if latest is not None else None
        latest_config=read_file(os.path.join(home,"config.yaml")); latest_config_hash=hashlib.sha256(latest_config or b"").hexdigest() if latest_config is not None else None
        latest_env_mode=stat.S_IMODE(os.lstat(path).st_mode) if latest is not None else None
        latest_config_mode=stat.S_IMODE(os.lstat(os.path.join(home,"config.yaml")).st_mode) if latest_config is not None else None
        if latest_hash!=expected_env or latest_config_hash!=expected_config or latest_env_mode!=expected_env_mode or latest_config_mode!=expected_config_mode or inspect(name).get("mode")!=expected_mode: os.unlink(tmp); return {"ok":False,"code":"stale_config","message":"Profile config changed while applying; no gateway was started."}
        os.replace(tmp,path)
        dfd=os.open(home,os.O_RDONLY|getattr(os,"O_DIRECTORY",0)); os.fsync(dfd); os.close(dfd)
    finally:
        try: os.unlink(tmp)
        except FileNotFoundError: pass
    return {"ok":True,"env_sha256":hashlib.sha256(content).hexdigest(),"backup_created":current is not None,"changed_fields":list(updates)}
try:
    req=json.loads(sys.stdin.buffer.read(MAX+1).decode("utf-8"))
    if not isinstance(req,dict): raise ValueError("invalid request")
    if req.get("op")=="inspect": result=inspect(req.get("profile"))
    elif req.get("op")=="profiles": result={"ok":True,"profiles":safe_profiles()}
    elif req.get("op")=="write_env": result=write_env(req.get("profile"),req.get("expected_env_sha256"),req.get("expected_config_sha256"),req.get("expected_env_mode"),req.get("expected_config_mode"),req.get("expected_mode"),req.get("updates"))
    elif req.get("op")=="start_gateway":
        try: result={"ok":True,**start_gateway(req.get("profile"),req.get("mode"),req.get("identity"))}
        except Exception as e:
            message=str(e).lower()
            if any(word in message for word in ("uncertain","unknown outcome","receipt","reservation","identity changed","could not be verified")):
                fail("gateway_start_unconfirmed","Gateway start or attachment could not be confirmed; inspect Hermes before retrying.")
            fail("gateway_start_failed","Hermes gateway could not be started safely.")
    else: raise ValueError("unsupported operation")
    print(json.dumps(result,separators=(",",":")))
except Exception:
    fail("guest_operation_failed","The selected WSL Hermes profile could not be inspected safely.")
'''


def _decode_wsl_output(raw: bytes) -> str:
    """Decode WSL's UTF-16LE console output without treating NUL as noise."""
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return raw.decode("utf-16", "strict")
    if raw and raw.count(b"\x00") > max(1, len(raw) // 5):
        try:
            return raw.decode("utf-16-le", "strict")
        except UnicodeDecodeError:
            pass
    return raw.decode("utf-8-sig", "strict")


def parse_distro_names(raw: bytes) -> list[str]:
    try:
        text = _decode_wsl_output(raw)
    except UnicodeDecodeError as error:
        raise WslHermesError("invalid_wsl_output", "WSL returned an unreadable distro list") from error
    names = []
    for line in text.splitlines():
        name = line.strip()
        if not name:
            continue
        if len(name) > 120 or any(ord(c) < 32 or ord(c) == 127 for c in name):
            raise WslHermesError("invalid_wsl_output", "WSL returned an invalid distro name")
        if name not in names:
            names.append(name)
    return names


def _windows_registry_distros() -> dict[str, str]:
    if os.name != "nt":
        return {}
    try:
        import winreg
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Lxss"
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path) as root:
            result: dict[str, str] = {}
            index = 0
            while True:
                try:
                    guid = winreg.EnumKey(root, index)
                except OSError:
                    break
                index += 1
                try:
                    with winreg.OpenKey(root, guid) as item:
                        name, _ = winreg.QueryValueEx(item, "DistributionName")
                    if isinstance(name, str) and name:
                        result[name] = guid
                except OSError:
                    continue
            return result
    except OSError:
        return {}


class WslHermesManager:
    """WSL orchestration primitives; service operations are added by Bridge."""

    def __init__(self, *, runner: Callable[..., Any] | None = None,
                 popen: Callable[..., Any] | None = None,
                 registry_reader: Callable[[], dict[str, str]] = _windows_registry_distros,
                 platform: str | None = None, wsl_executable: str | None = None):
        self._runner = runner
        self._popen = popen or subprocess.Popen
        self._registry_reader = registry_reader
        self._platform = platform or os.name
        self._wsl = wsl_executable or self._system_wsl_executable()
        self._known: dict[str, WslDistro] = {}
        self._lock = threading.RLock()
        self._closed = False
        self._operations: dict[str, WslOperation] = {}
        self._previews: dict[str, dict[str, Any]] = {}

    def _system_wsl_executable(self) -> str:
        if self._platform != "nt":
            return "wsl.exe"
        root = os.environ.get("SystemRoot") or os.environ.get("WINDIR") or r"C:\Windows"
        native = os.path.join(root, "Sysnative", "wsl.exe")
        system32 = os.path.join(root, "System32", "wsl.exe")
        return native if os.path.isfile(native) else system32

    def _run(self, args: list[str], *, input: bytes | None = None,
             timeout: float = _LIST_TIMEOUT) -> Any:
        try:
            if self._runner is not None:
                result = self._runner(args, input=input, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, timeout=timeout, check=False)
            else:
                result = self._bounded_run(args, input=input, timeout=timeout)
        except (OSError, subprocess.SubprocessError) as error:
            raise WslHermesError("wsl_unavailable", "WSL could not be queried") from error
        if getattr(result, "returncode", 1) != 0:
            raise WslHermesError("wsl_command_failed", "WSL returned an error")
        stdout = getattr(result, "stdout", b"")
        if not isinstance(stdout, bytes):
            stdout = str(stdout).encode("utf-8")
        if len(stdout) > _MAX_OUTPUT:
            raise WslHermesError("wsl_output_too_large", "WSL returned too much output")
        return result

    @staticmethod
    def _bounded_run(args: list[str], *, input: bytes | None, timeout: float) -> Any:
        process = subprocess.Popen(args, stdin=subprocess.PIPE if input is not None else subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, close_fds=True)
        outputs: dict[str, bytearray] = {"stdout": bytearray(), "stderr": bytearray()}
        overflow = threading.Event()
        def drain(name: str, stream) -> None:
            try:
                while True:
                    chunk = stream.read(8192)
                    if not chunk:
                        return
                    target = outputs[name]
                    if len(target) + len(chunk) > _MAX_OUTPUT:
                        target.extend(chunk[:max(0, _MAX_OUTPUT + 1 - len(target))])
                        overflow.set()
                        try: process.kill()
                        except OSError: pass
                        return
                    target.extend(chunk)
            finally:
                stream.close()
        readers = [threading.Thread(target=drain, args=(name, stream), daemon=True)
                   for name, stream in (("stdout", process.stdout), ("stderr", process.stderr))]
        for reader in readers: reader.start()
        writer_error = None
        if input is not None:
            try:
                assert process.stdin is not None
                process.stdin.write(input); process.stdin.close()
            except (BrokenPipeError, OSError) as error:
                writer_error = error
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            try: process.kill()
            except OSError: pass
            process.wait()
            raise
        for reader in readers: reader.join(timeout=2)
        if any(reader.is_alive() for reader in readers):
            try: process.kill()
            except OSError: pass
            raise WslHermesError("wsl_output_incomplete", "WSL output could not be bounded safely")
        if overflow.is_set():
            raise WslHermesError("wsl_output_too_large", "WSL returned too much output")
        if writer_error is not None and process.returncode == 0:
            raise writer_error
        return subprocess.CompletedProcess(args, process.returncode,
                                           bytes(outputs["stdout"]), bytes(outputs["stderr"]))

    @staticmethod
    def _id_for(registry: dict[str, str], name: str) -> str:
        guid = registry.get(name)
        if not isinstance(guid, str) or not re.fullmatch(r"\{[0-9A-Fa-f-]{36}\}", guid):
            raise WslHermesError("distro_identity_unavailable", "WSL distro identity could not be verified")
        return guid.lower()

    def list_distros(self) -> list[dict[str, str]]:
        if self._platform != "nt":
            return []
        all_result = self._run([self._wsl, "--list", "--quiet"])
        running_result = self._run([self._wsl, "--list", "--running", "--quiet"])
        names = parse_distro_names(all_result.stdout)
        running = set(parse_distro_names(running_result.stdout))
        registry = self._registry_reader()
        distros = []
        known: dict[str, WslDistro] = {}
        for name in names:
            distro_id = self._id_for(registry, name)
            row = WslDistro(distro_id, name, "running" if name in running else "stopped")
            known[distro_id] = row
            distros.append({"id": row.id, "name": row.name, "state": row.state, "version": row.version})
        with self._lock:
            self._known = known
        return distros

    def _selected(self, distro_id: str) -> WslDistro:
        if not isinstance(distro_id, str) or not re.fullmatch(r"\{[0-9a-fA-F-]{36}\}", distro_id):
            raise WslHermesError("invalid_distro", "Select a listed WSL distro first")
        with self._lock:
            row = self._known.get(distro_id.lower())
        if row is None:
            raise WslHermesError("stale_distro", "The WSL distro list changed; refresh and select again")
        # A display name is not an identity. Recheck the registry immediately
        # before every explicit guest action so name reuse cannot retarget it.
        registry = self._registry_reader()
        if self._id_for(registry, row.name) != row.id:
            raise WslHermesError("stale_distro", "The WSL distro identity changed; refresh and select it again")
        return row

    def _explicit_guest(self, row: WslDistro, command: list[str], *, timeout: float = _GUEST_TIMEOUT,
                        input_bytes: bytes | None = None) -> Any:
        # This method is called only from user-selected actions. It never uses
        # UNC or a shell command containing user-supplied values.
        current = self._selected(row.id)
        if current.name != row.name:
            raise WslHermesError("stale_distro", "The selected WSL distro identity changed; refresh and select it again")
        return self._run([self._wsl, "--distribution", row.name, "--exec", *command],
                         input=input_bytes, timeout=timeout)

    def start_selected_distro(self, distro_id: str) -> dict[str, str]:
        row = self._selected(distro_id)
        result = self._explicit_guest(row, ["/usr/bin/uname", "-r"], timeout=_START_TIMEOUT)
        try:
            kernel = _decode_wsl_output(result.stdout).strip()
        except UnicodeDecodeError as error:
            raise WslHermesError("wsl_version_unknown", "Could not verify this distro's WSL version") from error
        if "WSL2" not in kernel.upper():
            raise WslHermesError("wsl1_unsupported", "This WSL distro is not verified as WSL2")
        with self._lock:
            self._known[distro_id.lower()] = WslDistro(row.id, row.name, "running", "2")
        return {"id": row.id, "name": row.name, "state": "running", "version": "2"}

    def _guest_json(self, row: WslDistro, request: dict[str, Any], *, timeout: float = _GUEST_TIMEOUT) -> dict[str, Any]:
        try:
            bootstrap = self._explicit_guest(row, ["/usr/bin/env", "python3", "-c", _GUEST_BOOTSTRAP],
                                             timeout=timeout,
                                             input_bytes=json.dumps({"profile": request.get("profile", "default")}, separators=(",", ":")).encode("utf-8"))
            bootstrap_text = _decode_wsl_output(bootstrap.stdout)
            if len(bootstrap_text.encode("utf-8")) > _MAX_OUTPUT:
                raise WslHermesError("wsl_output_too_large", "Selected profile returned too much data")
            bootstrap_value = json.loads(bootstrap_text)
            if not isinstance(bootstrap_value, dict) or bootstrap_value.get("ok") is not True:
                raise WslHermesError(str(bootstrap_value.get("code", "guest_runtime_unavailable")),
                                     str(bootstrap_value.get("message", "Hermes runtime is unavailable")))
            guest_python = bootstrap_value.get("python")
            if not isinstance(guest_python, str) or not guest_python.startswith("/") or "\x00" in guest_python:
                raise WslHermesError("guest_runtime_unavailable", "A safe Hermes Python runtime could not be selected")
            python = self._explicit_guest(row, [guest_python, "-c", _GUEST_HELPER],
                                          timeout=timeout,
                                          input_bytes=json.dumps(request, separators=(",", ":")).encode("utf-8"))
            text = _decode_wsl_output(python.stdout)
            if len(text.encode("utf-8")) > _MAX_OUTPUT:
                raise WslHermesError("wsl_output_too_large", "Selected profile returned too much data")
            value = json.loads(text)
        except WslHermesError:
            raise
        except (UnicodeDecodeError, json.JSONDecodeError, OSError, subprocess.SubprocessError) as error:
            raise WslHermesError("guest_protocol_error", "WSL Hermes helper returned an invalid response") from error
        if not isinstance(value, dict) or value.get("ok") is not True:
            code = value.get("code", "guest_operation_failed") if isinstance(value, dict) else "guest_operation_failed"
            message = value.get("message", "Selected WSL Hermes profile is unavailable") if isinstance(value, dict) else "Selected WSL Hermes profile is unavailable"
            raise WslHermesError(str(code)[:80], str(message)[:240])
        return value

    def list_profiles(self, distro_id: str) -> list[dict[str, str]]:
        row = self._selected(distro_id)
        if row.state != "running":
            raise WslHermesError("distro_not_running", "Start the selected WSL distro to list Hermes profiles")
        value = self._guest_json(row, {"op": "profiles"})
        raw = value.get("profiles")
        if not isinstance(raw, list) or not raw or len(raw) > 257:
            raise WslHermesError("profile_list_unrecognized", "Hermes profiles could not be listed safely")
        if any(not isinstance(item, str) or not _PROFILE_ID.fullmatch(item) for item in raw) \
                or len(set(raw)) != len(raw) or "default" not in raw:
            raise WslHermesError("profile_list_unrecognized", "Hermes profiles could not be listed safely")
        return [{"id": profile, "label": "Hermes · " + profile, "api_state": "not_inspected"}
                for profile in raw]

    def inspect(self, distro_id: str, profile_id: str) -> dict[str, Any]:
        row = self._selected(distro_id)
        if row.state != "running":
            raise WslHermesError("distro_not_running", "The selected distro must be running before profile inspection")
        if not isinstance(profile_id, str) or not _PROFILE_ID.fullmatch(profile_id):
            raise WslHermesError("invalid_profile", "Select a Hermes profile from the listed profiles")
        value = self._guest_json(row, {"op": "inspect", "profile": profile_id})
        value["distro_id"] = row.id
        value["distro_name"] = row.name
        value["profile_id"] = profile_id
        return value

    def prepare_api_updates(self, distro_id: str, profile_id: str,
                            expected: dict[str, Any], mode: str) -> dict[str, Any]:
        """Return a private CAS plan and a value-free impact summary."""
        if mode not in {"default", "shared", "standalone"}:
            raise WslHermesError("gateway_mode_unknown", "无法确认 Hermes 网关模式；没有更改设置。请先启动或连接 default 配置，再选择具名配置；仍无法确认时查看 Hermes 状态。")
        selected = self.inspect(distro_id, profile_id)
        if selected.get("env_sha256") != expected.get("env_sha256") \
                or selected.get("config_sha256") != expected.get("config_sha256") \
                or selected.get("mode") != mode:
            raise WslHermesError("stale_config", "Profile settings changed; inspect them again")
        shared_secondary = mode == "shared" and profile_id != "default"
        profiles: list[tuple[str, dict[str, Any], dict[str, Any], str]] = []
        if shared_secondary:
            if selected.get("effective", {}).get("enabled") is True:
                raise WslHermesError("secondary_listener_enabled", "This named profile enables its own listener; resolve that setting manually before using the shared host")
            default = self.inspect(distro_id, "default")
            profiles.extend([("default", default, default.get("effective", {}), "default"),
                             (profile_id, selected, selected.get("effective", {}), "shared")])
        else:
            profiles.append((profile_id, selected, selected.get("effective", {}),
                             "default" if profile_id == "default" else mode))
        targets = []
        for name, snapshot, values, expected_mode in profiles:
            if snapshot.get("api_state") in {"unsafe_host", "invalid_port", "invalid_key"}:
                raise WslHermesError("api_config_invalid", "Selected Hermes API settings are unsafe or invalid")
            updates = {}
            if values.get("enabled") is not True: updates["API_SERVER_ENABLED"] = "true"
            if not values.get("key"): updates["API_SERVER_KEY"] = "__PADNOTE_GENERATE_API_SERVER_KEY__"
            if not values.get("host"): updates["API_SERVER_HOST"] = "127.0.0.1"
            if not values.get("port"): updates["API_SERVER_PORT"] = "8642"
            # A named profile on a shared host owns only its own key.
            if expected_mode == "shared": updates.pop("API_SERVER_ENABLED", None)
            targets.append({"profile": name,
                            "expected_env_sha256": snapshot.get("env_sha256"),
                            "expected_config_sha256": snapshot.get("config_sha256"),
                            "expected_env_mode": snapshot.get("env_mode"),
                            "expected_config_mode": snapshot.get("config_mode"),
                            "expected_mode": expected_mode, "updates": updates})
        if any(item["updates"] for item in targets) and any(snapshot.get("gateway") for _name,snapshot,_values,_mode in profiles):
            raise WslHermesError("restart_required", "A selected Hermes gateway is already running. No configuration was changed; review the API settings, restart that gateway yourself, then reconnect.")
        impact = [{"path_label": ".hermes/.env" if item["profile"] == "default"
                   else f".hermes/profiles/{item['profile']}/.env",
                   "fields": list(item["updates"]) or ["API_SERVER_KEY"],
                   "action": "update" if item["updates"] else "reuse"} for item in targets]
        return {"targets": targets, "impact": impact}

    def apply_api_updates(self, distro_id: str, plan: dict[str, Any]) -> list[dict[str, Any]]:
        row = self._selected(distro_id)
        results = []
        changed_any = False
        for target in plan.get("targets", []):
            if not isinstance(target, dict) or not isinstance(target.get("updates"), dict):
                raise WslHermesError("invalid_plan", "The API configuration plan is invalid")
            if not target["updates"]:
                current = self._guest_json(row, {"op": "inspect", "profile": target.get("profile")})
                if current.get("env_sha256") != target.get("expected_env_sha256") \
                        or current.get("config_sha256") != target.get("expected_config_sha256") \
                        or current.get("env_mode") != target.get("expected_env_mode") \
                        or current.get("config_mode") != target.get("expected_config_mode") \
                        or current.get("mode") != target.get("expected_mode"):
                    raise WslHermesError("stale_config", "Profile settings changed after review; inspect again before connecting")
                results.append({"profile": target["profile"], "applied": False, "status": "reused"})
                continue
            updates = {key: secrets.token_urlsafe(32) if value == "__PADNOTE_GENERATE_API_SERVER_KEY__" else value
                       for key, value in target["updates"].items()}
            try:
                value = self._guest_json(row, {"op": "write_env", "profile": target["profile"],
                                               "expected_env_sha256": target.get("expected_env_sha256"),
                                               "expected_config_sha256": target.get("expected_config_sha256"),
                                               "expected_env_mode": target.get("expected_env_mode"),
                                               "expected_config_mode": target.get("expected_config_mode"),
                                               "expected_mode": target.get("expected_mode"),
                                               "updates": updates}, timeout=12.0)
            except WslHermesError as error:
                if changed_any:
                    raise WslHermesError("partial_config", "Some reviewed API settings were updated, but another profile changed. No gateway was started; inspect both profiles again.") from error
                raise
            results.append({"profile": target["profile"], "applied": True,
                            "status": "updated", "fingerprint": value.get("env_sha256")})
            changed_any = True
        return results

    def submit_operation(self, distro_id: str, phase: str,
                         work: Callable[[WslOperation], dict[str, Any]]) -> str:
        self._selected(distro_id)
        operation = WslOperation(secrets.token_urlsafe(18), phase=phase,
                                 message="正在准备所选 WSL 操作。", distro_id=distro_id)
        with self._lock:
            if self._closed:
                raise WslHermesError("service_closed", "WSL operations are closed")
            if len(self._operations) >= 64:
                self._operations = {key: value for key, value in self._operations.items()
                                    if value.status in {"pending", "running"}}
            if len(self._operations) >= 64:
                raise WslHermesError("operation_capacity", "Too many WSL operations are active")
            self._operations[operation.operation_id] = operation

        def run() -> None:
            with self._lock:
                if operation.cancel_wait.is_set():
                    operation.status = "cancelled"
                    operation.phase = "cancelled"
                    operation.message = "已取消等待；未启动新的 WSL 操作。"
                    operation.updated_at = time.time()
                    return
                operation.status = "running"
                operation.updated_at = time.time()
            try:
                result = work(operation)
                with self._lock:
                    operation.result = result if isinstance(result, dict) else {}
                    operation.status = "succeeded"
                    operation.phase = "complete"
                    operation.message = str(operation.result.pop("message", "WSL 操作已完成。"))[:240]
                    operation.updated_at = time.time()
            except WslHermesError as error:
                with self._lock:
                    operation.status = "unknown" if error.code == "gateway_start_unconfirmed" else "failed"
                    operation.phase = error.code[:80]
                    operation.message = str(error)[:240]
                    operation.updated_at = time.time()
            except Exception:
                with self._lock:
                    operation.status = "unknown"
                    operation.phase = "outcome_unknown"
                    operation.message = "操作结果无法确认。请先检查 Hermes 状态，不要重复启动。"
                    operation.updated_at = time.time()

        worker = threading.Thread(target=run, name="padnote-wsl-" + operation.operation_id[:8], daemon=True)
        operation.worker = worker
        try:
            worker.start()
        except Exception as error:
            with self._lock:
                operation.status = "failed"
                operation.phase = "worker_start_failed"
                operation.message = "本机 WSL 操作无法启动。"
                operation.updated_at = time.time()
            raise WslHermesError("operation_start_failed", "The local WSL operation could not start") from error
        return operation.operation_id

    def close(self, timeout: float = 60.0) -> bool:
        with self._lock:
            self._closed = True
            workers = [item.worker for item in self._operations.values() if item.worker is not None]
        deadline = time.monotonic() + max(0.0, timeout)
        for worker in workers:
            worker.join(timeout=max(0.0, deadline - time.monotonic()))
        return all(not worker.is_alive() for worker in workers)

    def operation_status(self, operation_id: str) -> dict[str, Any]:
        if not isinstance(operation_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{16,64}", operation_id):
            raise WslHermesError("operation_not_found", "WSL operation not found")
        with self._lock:
            operation = self._operations.get(operation_id)
            if operation is None:
                raise WslHermesError("operation_not_found", "WSL operation not found")
            result = {key: value for key, value in operation.result.items()
                      if key in {"instance_id", "instance_name", "health", "features", "profiles"}}
            return {"operation_id": operation.operation_id, "status": operation.status,
                    "phase": operation.phase, "message": operation.message,
                    "distro_id": operation.distro_id, "profiles": copy_safe(operation.profiles),
                    "result": result, "updated_at": int(operation.updated_at)}

    def cancel_wait(self, operation_id: str) -> dict[str, Any]:
        with self._lock:
            operation = self._operations.get(operation_id)
            if operation is None:
                raise WslHermesError("operation_not_found", "WSL operation not found")
            if operation.status == "pending":
                operation.cancel_wait.set()
            elif operation.status == "running":
                operation.cancel_wait.set()
                operation.message = "已停止等待；当前操作会在后台继续，不会停止 Hermes 或 WSL。"
            operation.updated_at = time.time()
            return {"operation_id": operation.operation_id, "status": operation.status,
                    "message": operation.message}

    def begin_profile_discovery(self, distro_id: str) -> str:
        def work(operation: WslOperation) -> dict[str, Any]:
            operation.phase = "starting_distro"
            self.start_selected_distro(distro_id)
            operation.phase = "enumerating_profiles"
            profiles = self.list_profiles(distro_id)
            operation.profiles = profiles
            return {"profiles": profiles, "message": "已读取所选 WSL 发行版中的 Hermes 配置。"}
        return self.submit_operation(distro_id, "starting_distro", work)

    def create_preview(self, distro_id: str, profile_id: str) -> dict[str, Any]:
        snapshot = self.inspect(distro_id, profile_id)
        mode = snapshot.get("mode")
        if mode not in {"default", "shared", "standalone"}:
            raise WslHermesError("gateway_mode_unknown", "无法确认 Hermes 网关模式；没有更改设置。请先启动或连接 default 配置，再选择具名配置；仍无法确认时查看 Hermes 状态。")
        plan_mode = "default" if mode == "default" else mode
        plan = self.prepare_api_updates(distro_id, profile_id, snapshot, plan_mode)
        token = secrets.token_urlsafe(24)
        gateway = snapshot.get("gateway")
        api_state = snapshot.get("api_state")
        if mode == "shared" and profile_id != "default":
            default_snapshot = self.inspect(distro_id, "default")
            if snapshot.get("effective", {}).get("key") and default_snapshot.get("api_state") == "configured":
                api_state = "configured"
        requires_start = not bool(gateway) or (mode == "shared" and profile_id != "default"
                          and profile_id not in gateway.get("served_profiles", []))
        safe_snapshot = dict(snapshot)
        safe_effective = dict(snapshot.get("effective", {}))
        safe_effective.pop("key", None)
        safe_snapshot["effective"] = safe_effective
        preview = {"distro_id": distro_id, "profile_id": profile_id,
                   "mode": mode, "snapshot": safe_snapshot, "plan": plan,
                   "expires_at": time.monotonic() + 300}
        with self._lock:
            self._previews[token] = preview
            if len(self._previews) > 128:
                now = time.monotonic()
                self._previews = {key: item for key,item in self._previews.items()
                                  if item["expires_at"] > now}
        return {"distro_id": distro_id, "profile_id": profile_id,
                "preview_token": token, "mode": mode,
                "impact": plan["impact"], "api_state": api_state,
                "requires_enable": any("API_SERVER_ENABLED" in item["fields"] for item in plan["impact"]),
                "requires_start": requires_start,
                "message": "只会检查并配置 Hermes 本机 API 接口；不会修改模型供应商登录。"}

    def consume_preview(self, token: str) -> dict[str, Any]:
        if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{24,64}", token):
            raise WslHermesError("preview_expired", "连接预览已失效，请重新检查所选配置")
        with self._lock:
            preview = self._previews.pop(token, None)
        if preview is None or preview.get("expires_at", 0) <= time.monotonic():
            raise WslHermesError("preview_expired", "连接预览已失效，请重新检查所选配置")
        return preview

    def start_gateway(self, distro_id: str, profile_id: str, mode: str,
                      identity: dict[str, Any]) -> dict[str, Any]:
        if mode not in {"default", "shared", "standalone"}:
            raise WslHermesError("gateway_mode_unknown", "Gateway mode is not confirmed; connect the default profile first, then select a named profile and check Hermes status if the mode is still unknown")
        row = self._selected(distro_id)
        return self._guest_json(row, {"op": "start_gateway", "profile": profile_id,
                                      "mode": "shared" if mode == "shared" else "standalone",
                                      "identity": identity}, timeout=_START_TIMEOUT)


def copy_safe(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False))

