"""Android 宿主适配。规则来自发布的 mcpywrap-core，模块不实现依赖合并。"""
from contextlib import redirect_stdout, redirect_stderr
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
from urllib.parse import urlsplit
from urllib.request import urlopen
import uuid

WORKSPACE = Path('/sdcard/mcpy')
MAX_DOWNLOAD = 64 * 1024 * 1024


def _atomic(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.mcpy-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def _store(private):
    from mcpywrap_host import CoreStore
    return CoreStore(Path(private) / 'engineering/releases')


_external = None
REQUIRED_OPERATIONS = {'resolve', 'build', 'package', 'sync', 'init', 'mod', 'add', 'remove',
                       'script.list', 'script.run', 'prepare_run'}


def _require_operations(core):
    missing = REQUIRED_OPERATIONS - set(core.describe().get('operations', []))
    if missing:
        raise ValueError('工程核心缺少当前应用所需能力：' + ', '.join(sorted(missing)) + '；请使用 0.6.2 或兼容新版核心')
    return core

def _configure(private, Core):
    if _external is not None: return _require_operations(Core(_external.services(private)))
    from mcpywrap_core.host import HostServices
    options = dict(python_executable=None, allow_processes=False,
                   lock_directory=Path(private)/'engineering/locks', cache_directory=WORKSPACE/'cache/mcpy')
    if 'project_metadata_files' in HostServices.__dataclass_fields__:
        options['project_metadata_files'] = ('.mcpy-project.json',)
    return _require_operations(Core(HostServices(**options)))


def _load(private):
    core = _store(private).load_selected(configure=lambda Core: _configure(private, Core))
    if core is None:
        from mcpywrap_core.api import Core
        core = _configure(private, Core)
    return core


def _project(folder):
    if not isinstance(folder, str) or not folder or folder in ('.', '..') or '/' in folder or '\\' in folder:
        raise ValueError('请先选择作品')
    root = (WORKSPACE/'projects').resolve()
    path = root/folder
    if path.is_symlink() or path.resolve().parent != root or not path.is_dir():
        raise ValueError('作品目录不存在或包含链接')
    identity = json.loads((path/'.mcpy-project.json').read_text())['id']
    if not re.fullmatch(r'[A-Za-z0-9-]{1,80}', identity): raise ValueError('作品标识无效')
    return path, identity


def _initialize_project(path, identity):
    """将原普通组件提升为标准工程：只新增 TOML，不改已有源码或配置。"""
    config = path/'pyproject.toml'
    if config.exists(): return
    from mcpywrap_core.dependencies import addon_directories, write_project
    addon_directories(path)
    write_project(path, {'project': {'name': 'mcpy-'+identity[:8], 'version': '0.1.0', 'dependencies': []},
                        'tool': {'mcpywrap': {'project_type': 'addon',
                        'target_dir': 'build'}}})


def _validate_public_configuration(path, seen=None):
    from mcpywrap_core.dependencies import read_project
    seen = set() if seen is None else seen
    path = Path(path).resolve()
    if not path.is_relative_to(WORKSPACE.resolve()): raise ValueError('工程及依赖必须位于 /sdcard/mcpy')
    if path in seen: return
    seen.add(path)
    config = read_project(path).get('tool', {}).get('mcpywrap', {})
    if config.get('project_type','addon') != 'addon': raise ValueError('手机宿主暂不支持地图工程')
    target = config.get('target_dir')
    if target:
        output = (path/target).resolve()
        if not (output.is_relative_to(path) or output.is_relative_to((WORKSPACE/'build').resolve())):
            raise ValueError('构建目标必须位于本作品或公共 build 目录')
    for name in config.get('local_dependencies', []):
        _validate_public_configuration(path/name, seen)


def _validate_inputs(path):
    from mcpywrap_core.dependencies import read_project, DependencyService
    from mcpywrap_core.host import CapabilityUnavailable
    _validate_public_configuration(path)
    config = read_project(path)
    settings = config.get('tool', {}).get('mcpywrap', {})
    if settings.get('project_type', 'addon') != 'addon':
        raise CapabilityUnavailable('map_project', '当前手机版支持 Addon 工程，地图工程尚未接入。')
    manager = DependencyService(path).resolve()
    packs = [manager.root_node.addon_pack, *manager.get_all_dependencies().values()]
    for pack in packs:
        source = Path(pack.path).resolve()
        if not source.is_relative_to(WORKSPACE.resolve()):
            raise ValueError('工程依赖必须位于 /sdcard/mcpy：'+str(source))
        own = read_project(source).get('tool', {}).get('mcpywrap', {})
        if own.get('code_libraries') and _external is None:
            raise CapabilityUnavailable('code_libraries', '此作品需要准备代码库依赖。请在「开发工具」连接开发端，再使用标准 mcpy sync 命令。')
        if (own.get('hooks') or own.get('consumer_prepare')) and _external is None:
            raise CapabilityUnavailable('project_scripts', '此作品需要构建脚本。请在「开发工具」连接开发端，再使用标准 mcpy 命令运行。')
    return packs, settings


def _fingerprint(packs):
    # 只检查构建输入，忽略工作日志和输出；含依赖源码及工程配置。
    digest = hashlib.sha256()
    for pack in packs:
        root = Path(pack.path)
        candidates = [root/name for name in ('pyproject.toml', 'mcpy-git.lock.json', 'mcpy-code-libraries.lock.json')]
        for kind in ('behavior_pack_dir', 'resource_pack_dir'):
            directory = getattr(pack, kind, None)
            if directory and Path(directory).is_dir():
                candidates.extend(sorted(Path(directory).rglob('*')))
        for file in candidates:
            if file.is_symlink(): raise ValueError('源码中存在链接：'+str(file))
            if file.is_file():
                digest.update(str(file).encode()); digest.update(b'\0'); digest.update(file.read_bytes())
    return digest.hexdigest()


def _engineering(private, operation, folder, notify):
    notify("正在读取作品配置")
    core = _load(private)
    path, identity = _project(folder)
    _initialize_project(path, identity)
    from mcpywrap_core.host import host_scope
    with host_scope(core.host):
        notify("正在解析组件依赖")
        packs, settings = _validate_inputs(path)
        if operation == 'build':
            target = settings.get('target_dir')
            if not target: raise ValueError('pyproject.toml 中缺少 tool.mcpywrap.target_dir，请设置构建输出目录')
            output = (path/target).resolve()
            if not (output.is_relative_to(path) or output.is_relative_to((WORKSPACE/'build').resolve())):
                raise ValueError('构建输出必须位于作品目录或 /sdcard/mcpy/build，不能写入其他作品或存档')
        before = _fingerprint(packs)
        notify({'build': '正在组装行为包和资源包', 'package': '正在组装并打包组件', 'sync': '正在同步工程配置', 'resolve': '正在检查依赖关系'}[operation])
        result = core.execute(operation, path)
        if before != _fingerprint(packs) and operation != 'sync':
            raise ValueError('构建期间源码发生变化，请保存后重新测试；没有启动游戏')
        result['coreVersion'] = core.describe()['version']
        if operation == 'package':
            notify('正在保存导出文件')
            artifact = Path(result['artifact'])
            export = WORKSPACE/'exports'/(folder+'-'+str(time.time_ns())+'.zip')
            _atomic(export, artifact.read_bytes())
            result['artifact'] = str(export)
        return result


def _catalog(version=None):
    if version is not None and not re.fullmatch(r'\d+\.\d+\.\d+(?:(?:a|b|rc)\d+)?', version):
        raise ValueError('无效的核心版本')
    url = 'https://pypi.org/pypi/mcpywrap-core/'+(version+'/' if version else '')+'json'
    with urlopen(url, timeout=30) as response:
        if urlsplit(response.url).hostname != 'pypi.org': raise ValueError('更新源发生异常跳转')
        data = response.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024: raise ValueError('更新信息过大')
    document = json.loads(data)
    from packaging.version import Version
    selected = document['info']['version']
    if Version(selected).is_prerelease or Version(selected).is_devrelease: raise ValueError('仅支持正式发布的核心版本')
    if version and Version(selected) != Version(version): raise ValueError('更新源版本不匹配')
    files = [entry for entry in document['urls'] if entry['filename'].endswith('-py3-none-any.whl') and not entry.get('yanked')]
    if len(files) != 1: raise ValueError('未找到兼容的核心更新包')
    item = files[0]
    parsed = urlsplit(item['url'])
    if parsed.scheme != 'https' or parsed.hostname != 'files.pythonhosted.org' or parsed.username:
        raise ValueError('核心下载来源不受支持')
    if item['size'] > MAX_DOWNLOAD: raise ValueError('核心包超过下载限制')
    return {'version': selected, 'url': item['url'], 'sha256': item['digests']['sha256'], 'size': item['size']}


def _health(core):
    _require_operations(core)
    # 在公开测试目录执行真正的空资源包导出，不触碰用户作品。
    from mcpywrap_core.dependencies import write_project
    temp = WORKSPACE/'build'/('core-check-'+uuid.uuid4().hex)
    try:
        pack = temp/'behavior_pack'; pack.mkdir(parents=True)
        manifest = {'format_version': 1, 'header': {'name': 'core-check', 'description': 'core-check',
            'uuid': str(uuid.uuid4()), 'version': [1, 0, 0]},
            'modules': [{'type': 'data', 'uuid': str(uuid.uuid4()), 'version': [1, 0, 0]}]}
        (pack/'manifest.json').write_text(json.dumps(manifest))
        write_project(temp, {'project': {'name': 'core-check', 'version': '0.0.0'},
                            'tool': {'mcpywrap': {'target_dir': 'build'}}})
        core.execute('resolve', temp); core.execute('build', temp); core.execute('package', temp)
        # 新建向导也依赖共享生成器；升级前验证该入口，避免切换到无法创建作品的核心。
        from new_project import generate
        generate(WORKSPACE, {'title': 'core-check-'+uuid.uuid4().hex, 'modName': 'CoreCheck',
            'version': '1.0.0', 'scriptDir': 'CheckScripts', 'serverSystem': 'CheckServer',
            'clientSystem': 'CheckClient'}, core, preview=True)
    finally:
        shutil.rmtree(temp, ignore_errors=True)


def _update(private, version, notify):
    notify("正在获取核心版本信息")
    # 重新从固定 HTTPS PyPI 源获取摘要，不信任公共目录中的更新描述。
    candidate = _catalog(version)
    directory = Path(private)/'engineering/downloads'; directory.mkdir(parents=True, exist_ok=True)
    destination = directory/(candidate['sha256']+'.whl')
    try:
        notify('正在下载工程核心')
        digest = hashlib.sha256(); size = 0
        with urlopen(candidate['url'], timeout=30) as response, destination.open('wb') as output:
            if urlsplit(response.url).hostname != 'files.pythonhosted.org': raise ValueError('核心下载发生异常跳转')
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk: break
                size += len(chunk)
                if size > MAX_DOWNLOAD: raise ValueError('核心包过大')
                digest.update(chunk); output.write(chunk)
        if size != candidate['size'] or digest.hexdigest() != candidate['sha256']: raise ValueError('核心包校验失败')
        notify('正在校验并安装核心文件')
        store = _store(private)
        store.stage(destination, expected_sha256=candidate['sha256'], expected_version=candidate['version'])
        notify('正在验证新核心的构建和生成能力')
        core = store.activate(candidate['sha256'], configure=lambda Core: _configure(private, Core), health_check=_health)
        return {'version': core.describe()['version'], 'selection': store.selection(), 'verified': True}
    finally:
        destination.unlink(missing_ok=True)


def execute(private, raw, progress=None):
    notify = (lambda label: progress.report(label)) if progress is not None else (lambda label: None)
    global _external
    request = json.loads(raw)
    if request.get('external_preparation'):
        from external_host import ExternalHost
        _external = ExternalHost(progress)
    else: _external = None
    task = uuid.uuid4().hex
    log = WORKSPACE/'logs'/('engineering-'+task+'.log')
    try:
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open('w', encoding='utf-8') as stream, redirect_stdout(stream), redirect_stderr(stream):
            operation = request.get('operation')
            if operation in ('preview_project', 'create_project'):
                from new_project import generate
                result = generate(WORKSPACE, request.get('options'), _load(private), preview=operation=='preview_project', notify=notify)
            elif operation == 'command':
                core = _load(private)
                path, identity = _project(request['folder'])
                options = request.get('options', {})
                if options.get('operation') != 'init': _initialize_project(path, identity)
                from mcpywrap_core.host import host_scope
                with host_scope(core.host):
                    _validate_public_configuration(path)
                    params = options.get('parameters', {})
                    if options.get('operation') == 'init':
                        output = (path/params.get('target_dir','build')).resolve()
                        if not (output.is_relative_to(path) or output.is_relative_to((WORKSPACE/'build').resolve())):
                            raise ValueError('构建目标必须位于本作品或公共 build 目录')
                    if params.get('local_path'):
                        dependency = (path/params['local_path']).resolve()
                        if not dependency.is_relative_to(WORKSPACE.resolve()): raise ValueError('本地依赖必须位于公共工作区')
                    result = core.execute(options['operation'], path, **params)
                result['coreVersion'] = core.describe()['version']
            elif operation == 'status':
                notify('正在读取工程核心状态')
                core = _load(private)
                from jsonschema import Draft202012Validator
                Draft202012Validator({'type':'integer'}).validate(7)
                result = {'schema_validation':'2020-12', 'version': core.describe()['version'], 'capabilities': core.describe(), 'selection': _store(private).selection()}
            elif operation == 'check_update':
                notify('正在连接更新源')
                core = _load(private)
                result = _catalog()
                from packaging.version import Version
                result['available'] = Version(result['version']) > Version(core.describe()['version'])
            elif operation == 'update':
                result = _update(private, request.get('version'), notify)
            elif operation == 'rollback':
                notify('正在恢复上一版核心')
                _store(private).rollback(); core = _load(private)
                result = {'version': core.describe()['version'], 'selection': _store(private).selection()}
            elif operation in ('resolve', 'build', 'package', 'sync'):
                result = _engineering(private, operation, request.get('folder'), notify)
            else:
                raise ValueError('不支持的工程操作')
        return json.dumps({'ok': True, 'result': dict(result, task=task, log=str(log))}, ensure_ascii=False)
    except Exception as error:
        import traceback
        if log.parent.exists():
            with log.open('a', encoding='utf-8') as stream: traceback.print_exc(file=stream)
        return json.dumps({'ok': False, 'error': str(error), 'code': getattr(error, 'code', 'engineering_failed'),
                           'hint': getattr(error, 'hint', None), 'details': dict(getattr(error, 'details', None) or {}, log=str(log))}, ensure_ascii=False)
