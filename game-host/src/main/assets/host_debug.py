# coding: utf-8
"""Session-owned debug helpers; never packaged into a user's component."""
import sys
import types
import json
import traceback
import os

_module = types.ModuleType('_mcpy_host')
_namespace = {'__name__': '__mcpy_debug__'}


def execute(source, side="client"):
    if isinstance(source, str): source = source.decode("utf-8-sig")
    source = "# coding: utf-8\n" + source.encode("utf-8")
    class Capture(object):
        def __init__(self): self.parts = []
        def write(self, text):
            if sum(len(p) for p in self.parts) < 131072:
                self.parts.append(text if isinstance(text, unicode) else str(text).decode('utf-8', 'replace'))
        def flush(self): pass
    out, err = Capture(), Capture()
    before = sys.stdout, sys.stderr
    value, error = None, None
    try:
        sys.stdout, sys.stderr = out, err
        _namespace.pop('_result', None)
        try:
            compiled = compile(source, '<mcpy-runtime>', 'eval')
        except SyntaxError:
            exec compile(source, '<mcpy-runtime>', 'exec') in _namespace
            value = _namespace.get('_result')
        else:
            value = eval(compiled, _namespace)
    except BaseException:
        error = traceback.format_exc()
    finally:
        sys.stdout, sys.stderr = before
    _emit(side, 'stdout', u''.join(out.parts))
    _emit(side, 'stderr', u''.join(err.parts))
    if error: _emit(side, 'error', error)
    try: json.dumps(value)
    except (TypeError, ValueError): value = repr(value)
    return {'state': 'failed' if error else 'completed', 'side': side, 'value': value,
            'stdout': u''.join(out.parts), 'stderr': u''.join(err.parts), 'error': error}

_module.execute = execute
sys.modules['_mcpy_host'] = _module


def server_execute(encoded_source, encoded_path):
    import base64
    import mod.client.extraClientApi as client_api
    system = client_api.GetSystem('Minecraft', 'safaia')
    if system is None: raise RuntimeError('Server debug relay is not ready')
    script = "import base64, json, os, sys\n"
    script += "_host_result = sys.modules['_mcpy_host'].execute(base64.b64decode(%r), 'server')\n" % encoded_source
    script += "_host_path = base64.b64decode(%r)\n" % encoded_path
    script += "with open(_host_path+'.tmp','w') as _host_file: json.dump(_host_result, _host_file)\n"
    script += "os.rename(_host_path+'.tmp',_host_path)\n"
    system.RunInServer(script)
    return {'state': 'queued', 'side': 'server'}

_module.server_execute = server_execute


def _emit(side, stream, text):
    try:
        import time
        if isinstance(text, str): text = text.decode('utf-8', 'replace')
        if not text: return
        path = globals().get('SESSION_LOG')
        if not path: return
        if os.path.exists(path) and os.path.getsize(path) > 16*1024*1024:
            os.rename(path, path+'.previous')
        with open(path, 'ab') as output:
            output.write(json.dumps({'time': int(time.time()*1000), 'side': side, 'stream': stream, 'text': text[:65536]})+'\n')
    except Exception:
        pass


class _LogTee(object):
    def __init__(self, original, stream): self.original, self.stream = original, stream
    def write(self, text):
        side = 'client'
        try:
            import mod.server.extraServerApi as server_api
            if server_api.GetLevelId() is not None: side = 'server'
        except Exception: pass
        _emit(side, self.stream, text)
        return self.original.write(text)
    def flush(self): return self.original.flush()
    def __getattr__(self, name): return getattr(self.original, name)


def install_logging():
    for name in ('stdout', 'stderr'):
        previous = getattr(sys, name)
        if not isinstance(previous, _LogTee): setattr(sys, name, _LogTee(previous, name))
    return True

_module.install_logging = install_logging
install_logging()
