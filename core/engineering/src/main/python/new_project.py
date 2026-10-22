"""新建工程宿主适配：桌面同源生成器，真实临时文件驱动预览。"""
import hashlib
import json
import keyword
from pathlib import Path
import shutil
import uuid


def validate(options):
    from packaging.version import Version, InvalidVersion
    if not isinstance(options, dict): raise ValueError('无效的新建参数')
    title = options.get('title', '').strip()
    if (not title or len(title) > 80 or title.startswith('.') or
            any(ord(c) < 32 or ord(c) == 127 or c in '/\\:*?"<>|' for c in title)):
        raise ValueError('作品名称不能包含路径符号，最多 80 个字符')
    values = {'title': title}
    for key, label in (('modName', 'Mod 标识'), ('scriptDir', '脚本目录'),
                       ('serverSystem', '服务端系统名'), ('clientSystem', '客户端系统名')):
        value = options.get(key, '').strip()
        if (not value or len(value) > 96 or not value.isascii() or not value.isidentifier()
                or keyword.iskeyword(value) or value in ('print', 'exec', '__init__')):
            raise ValueError(label+'需要使用字母、数字和下划线，不能以数字开头或使用 Python 保留字')
        values[key] = value
    raw_version = options.get('version', '').strip()
    if not raw_version or len(raw_version) > 64: raise ValueError('请填写模组版本，例如 1.0.0')
    try:
        values['version'] = str(Version(raw_version))
    except InvalidVersion as error:
        raise ValueError('模组版本无效，请使用 1.0.0 这样的版本号') from error
    return values


def _signature(stage, options, version):
    digest = hashlib.sha256(json.dumps({'options': options, 'core': version}, sort_keys=True).encode())
    for path in sorted(stage.rglob('*')):
        digest.update(path.relative_to(stage).as_posix().encode()); digest.update(b'\0')
        if not path.is_file(): continue
        content = path.read_bytes()
        if path.name in ('manifest.json', 'pack_manifest.json'):
            # 每次生成新的包 UUID；预览与创建比对其余真实内容。
            manifest = json.loads(content)
            manifest['header']['uuid'] = '<new-uuid>'
            for module in manifest['modules']: module['uuid'] = '<new-uuid>'
            content = json.dumps(manifest, sort_keys=True).encode()
        digest.update(content)
    return digest.hexdigest()


def generate(workspace, options, core, preview=True, notify=lambda label: None):
    from mcpywrap_core.host import host_scope
    from mcpywrap_core.project_init import initialize_project
    from mcpywrap_core.minecraft.template.generate_mod_files import generate_mod_framework
    values = validate(options)
    workspace = Path(workspace)
    if (workspace/'projects'/values['title']).exists() or (workspace/'worlds'/values['title']).exists():
        raise ValueError('已有同名作品或实例目录，请换一个作品名称')
    stage = workspace/'build'/('new-mod-'+uuid.uuid4().hex)
    stage.mkdir(parents=True)
    keep = False
    try:
        with host_scope(core.host):
            notify('正在初始化工程结构')
            initialize_project(stage, name=values['modName'].lstrip('_') or 'mod', project_type='addon',
                               version=values['version'], description=values['title'], target_dir='./build')
            script = values['scriptDir']
            server, client = values['serverSystem'], values['clientSystem']
            server_path = f'{script}.server.{server}.{server}'
            client_path = f'{script}.client.{client}.{client}'
            notify('正在生成客户端和服务端源码')
            ok, message = generate_mod_framework(str(stage/'behavior_pack'), values['modName'], values['version'],
                server, server_path, client, client_path, script)
            if not ok: raise ValueError(message)
            notify('正在核对文件结构')
            version = core.describe()['version']
            signature = _signature(stage, values, version)
            result = {'options': values, 'coreVersion': version, 'signature': signature,
                      'serverClass': server_path, 'clientClass': client_path,
                      'files': [p.relative_to(stage).as_posix()+('/' if p.is_dir() else '') for p in sorted(stage.rglob('*'))],
                      'entryFile': f'behavior_pack/{script}/modMain.py'}
            if not preview:
                if options.get('previewSignature') != signature:
                    raise ValueError('配置或核心模板已变化，请重新查看结构后创建')
                result['stage'] = str(stage)
                keep = True
            return result
    finally:
        if not keep: shutil.rmtree(stage, ignore_errors=True)
