# coding: utf-8
"""Python 2 developer adapter, installed before world startup via the JNI bridge.

SOURCE_ROOTS is supplied by the host for explicitly selected behavior packs.
Only those directories use the interpreter's ordinary source importer. The
game's MCP importer and ModSDK lifecycle continue to handle all other modules.
"""
import imp
import os
import sys
import json


def _inside(path, root):
    path, root = os.path.realpath(path), os.path.realpath(root)
    return path == root or path.startswith(root + os.sep)


def _root_for(path):
    for root in _source_roots:
        if _inside(path, root):
            return root
    return None


class _DeveloperSourceImporter(object):
    def __init__(self, directory):
        if not os.path.isdir(directory) or _root_for(directory) is None:
            raise ImportError('Outside developer source roots')
        self.directory = os.path.realpath(directory)

    def _find(self, fullname):
        leaf = fullname.rsplit('.', 1)[-1]
        if not leaf or '/' in leaf or '\\' in leaf:
            raise ImportError('Invalid source module name')
        handle, filename, description = imp.find_module(leaf, [self.directory])
        valid = _root_for(filename) is not None
        if description[2] == imp.PKG_DIRECTORY:
            init = os.path.join(filename, '__init__.py')
            valid = valid and os.path.isfile(init) and _root_for(init) is not None
        else:
            valid = valid and description[2] == imp.PY_SOURCE
        if not valid:
            if handle:
                handle.close()
            raise ImportError('Developer loader accepts only source packages/modules')
        return handle, filename, description

    def find_module(self, fullname, path=None):
        try:
            handle, _, _ = self._find(fullname)
            if handle:
                handle.close()
            return self
        except ImportError:
            return None

    def load_module(self, fullname):
        if fullname in sys.modules:
            return sys.modules[fullname]
        handle, filename, description = self._find(fullname)
        previous = sys.dont_write_bytecode
        try:
            sys.dont_write_bytecode = True
            return imp.load_module(fullname, handle, filename, description)
        finally:
            sys.dont_write_bytecode = previous
            if handle:
                handle.close()


def _load_developer_paths(self, paths, side):
    selected, remaining = [], []
    for path in paths:
        root = _root_for(path)
        if root is None:
            remaining.append(path)
            continue
        relative = os.path.relpath(os.path.realpath(path), root)
        parts = relative.split(os.sep)
        if not parts or parts[-1] != 'modMain.py' or '..' in parts:
            raise ValueError('Expected a modMain.py inside the selected behavior pack')
        selected.append('.'.join(parts)[:-3])
    if '_mcpy_host' in sys.modules:
        sys.modules['_mcpy_host'].install_logging()
    for module in selected:
        # Preserve the original Windows entry's optional modGoodsMain behavior.
        previous = self.loadingWindows
        try:
            self.loadingWindows = True
            self.ImportModAndInit(module, side, False)
            _loaded_source_sides.add((module, side))
        finally:
            self.loadingWindows = previous
    other_loaded = _original_windows(self, remaining, side) if remaining else False
    return bool(selected) or bool(other_loaded)


def _normalize_runtime_path(path):
    if not isinstance(path, basestring): return path
    choices = [path]
    if isinstance(path, str):
        try: choices.append(path.decode('gbk').encode('utf-8'))
        except UnicodeError: pass
    for candidate in choices:
        if _root_for(candidate) is not None: return candidate
    return path


def _load_developer_server(self, addon_path, mod_main_paths, mcp_paths):
    result = _original_load_mod(self, addon_path, mod_main_paths, mcp_paths)
    if _root_for(addon_path) is None: return result
    # Android's native server branch loads MCP but skips the Windows source entry.
    # Run only the engine-discovered, explicitly selected source modules, on this
    # original server load callback. A Windows path already handled above is not repeated.
    pending = []
    for path in mod_main_paths:
        root = _root_for(path)
        if root is not None:
            module = os.path.relpath(path, root).replace(os.sep, '.')[:-3]
            if (module, 'InitServer') not in _loaded_source_sides: pending.append(path)
    if pending: _load_developer_paths(self, pending, 'InitServer')
    self.serverModRuntimePaths = [_normalize_runtime_path(p) for p in self.serverModRuntimePaths]
    for key, value in self.serverModRuntimePathDict.items():
        self.serverModRuntimePathDict[key] = _normalize_runtime_path(value)
    if SOURCE_STATUS:
        import mod.server.extraServerApi as server_api
        with open(SOURCE_STATUS + '.tmp', 'w') as stream:
            json.dump({'side': 'server', 'level_id': server_api.GetLevelId(),
                       'modules': sorted(module for module, side in _loaded_source_sides if side == 'InitServer')}, stream)
        os.rename(SOURCE_STATUS + '.tmp', SOURCE_STATUS)
    return result


SOURCE_STATUS = globals().get("SOURCE_STATUS")
_loaded_source_sides = set()
_source_roots = tuple(os.path.realpath(root) for root in SOURCE_ROOTS)
if not _source_roots or any(not os.path.isdir(root) for root in _source_roots):
    raise ValueError('Developer behavior pack source roots must exist')
if getattr(sys, '_netease_developer_source_roots', None) is not None:
    if tuple(sys._netease_developer_source_roots) != _source_roots:
        raise RuntimeError('Restart the game to change developer source roots')
else:
    import common.minecraftMod as _minecraft_mod
    _original_windows = _minecraft_mod.MinecraftMod.LoadWindowsAddonPy
    _original_load_mod = _minecraft_mod.MinecraftMod.load_mod
    sys.path_hooks.insert(0, _DeveloperSourceImporter)
    for _root in reversed(_source_roots):
        if _root not in sys.path: sys.path.insert(0, _root)
    for _path in list(sys.path_importer_cache):
        if _root_for(_path) is not None:
            del sys.path_importer_cache[_path]
    _minecraft_mod.MinecraftMod.LoadWindowsAddonPy = _load_developer_paths
    _minecraft_mod.MinecraftMod.load_mod = _load_developer_server
    sys._netease_developer_source_roots = _source_roots
    print('[DeveloperSource] Enabled %d explicitly selected behavior pack(s)' % len(_source_roots))
