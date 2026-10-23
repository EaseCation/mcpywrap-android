"""Host capability adapters. The caller runs tool stages; this process owns the core."""
import base64
import json
from pathlib import Path
import subprocess
from importlib import metadata

ROOT = Path('/sdcard/mcpy').resolve()


def public_path(value):
    path = Path(value).resolve()
    if path != ROOT and ROOT not in path.parents:
        raise ValueError('准备结果路径不在公共工作区')
    return path


class PreparedDistribution:
    def __init__(self, value):
        self.version = value['version']
        self.metadata = {'Name': value['name']}
        self.root = str(public_path(value['root'])) if value.get('root') else None

    def read_text(self, name):
        return json.dumps({'url': Path(self.root).as_uri(), 'dir_info': {}}) if name == 'direct_url.json' and self.root else None


class ExternalHost:
    def __init__(self, reporter):
        self.reporter = reporter
        self.distributions = {}
        self.environments = {}
        self.marker_values = None

    def ask(self, kind, options):
        from mcpywrap_core import __version__
        from mcpywrap_core.host import CapabilityUnavailable
        if self.reporter is None: raise CapabilityUnavailable('external_preparation')
        response = json.loads(str(self.reporter.prepare(json.dumps({'kind': kind, 'options': options, 'core_version': __version__}))))
        if not response.get('ok'):
            error = ValueError(response.get('error') or '开发端准备失败')
            error.code = response.get('code', 'external_preparation_failed')
            error.hint = response.get('hint')
            error.details = response.get('details', {})
            raise error
        return response.get('result')

    def distribution(self, name):
        if name not in self.distributions:
            self.distributions[name] = self.ask('distribution', {'name': name})
        result = self.distributions[name]
        if result is None: raise metadata.PackageNotFoundError(name)
        return PreparedDistribution(result)

    def content(self, result, name):
        if result.get(name+'_path'):
            path = public_path(result[name+'_path'])
            if (ROOT/'build/tool-results') not in path.parents: raise ValueError('无效工具结果位置')
            if path.stat().st_size > 512*1024*1024: raise ValueError('工具结果过大')
            data=path.read_bytes();path.unlink();return data
        return base64.b64decode(result.get(name+'_b64',''), validate=True)

    def git_source(self, url, ref):
        result=self.ask('git_source',{'url':url,'ref':ref})
        return result['commit'],self.content(result,'stdout')

    def command(self, argv, **kwargs):
        argv = [str(v) for v in argv]
        if argv[:4] != ['host:python','-m','pip','install']:
            from mcpywrap_core.host import CapabilityUnavailable
            raise CapabilityUnavailable('tool_command', '只能委托依赖安装和已声明的项目任务')
        result = self.ask('pip', {'argv': argv})
        out, err = self.content(result,'stdout'), self.content(result,'stderr')
        if kwargs.get('text') or kwargs.get('encoding') or kwargs.get('universal_newlines'):
            out, err = out.decode(kwargs.get('encoding','utf-8'),kwargs.get('errors','replace')), err.decode(kwargs.get('encoding','utf-8'),kwargs.get('errors','replace'))
        self.distributions.clear()
        return subprocess.CompletedProcess(argv,result['returncode'],out,err)

    def script(self, project, name, spec, event, extra, **kwargs):
        return self.ask('script', {'project': str(project), 'name': name, 'spec': spec, 'event': event, 'extra':list(extra),
                                  'package_root': str(kwargs['package_root']) if kwargs.get('package_root') else None})

    def inspect(self, project, requirements, override):
        key = str(project)
        if key not in self.environments:
            self.environments[key] = self.ask('environment',{'project':key,'requirements':requirements,'override':override,'ensure':False})
        return self.environments[key]

    def ensure(self, project, requirements, override):
        value = self.ask('environment',{'project':str(project),'requirements':requirements,'override':override,'ensure':True})
        self.environments[str(project)] = value
        return value

    def identity(self, environment): return environment.get('identity')

    def markers(self):
        if self.marker_values is None:
            self.marker_values = self.ask('markers', {})
        return self.marker_values

    def services(self, private):
        from mcpywrap_core.host import HostServices
        return HostServices(python_executable='host:python',allow_processes=True,project_metadata_files=('.mcpy-project.json',),
            lock_directory=Path(private)/'engineering/locks',cache_directory=ROOT/'cache/mcpy',
            command_runner=self.command,script_runner=self.script,environment_provider=self,
            distribution_provider=self.distribution,marker_environment=self.markers,git_source_provider=self.git_source,
            project_script_inspector=lambda project: self.ask('script_recovery',{'project':str(project)}))
