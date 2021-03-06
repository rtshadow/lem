from fabric.operations import put, run, local, sudo, get
from fabric.context_managers import cd, prefix, hide, settings
from fabric.api import execute
import os
import datetime

def deploy(jar, classpath):
   with cd('/tmp'):
     with hide('running', 'stdout'):
       run('mkdir -p libs')
       put_if_absent(classpath.split(';'), 'libs')
       remote_jar = put(jar, 'libs/user.jar')[0]


def run_jar_silently(jar, classpath, class_name, arguments=''):
  with settings(warn_only=True):
    run_jar(jar, classpath, class_name, arguments)


def run_jar(jar, classpath, class_name, arguments=''):
  """Deploy and run hadoop job

  Arguments:
  jar - jar with the job's class
  class_name - class to run
  classpath - semicolon-delimited list of jars, that should be used as hadoop's classpath
  """
  with cd('/tmp'):
    hadoop_classpath =['/tmp/libs/' + os.path.basename(path) for path in classpath.split(';') + ['user.jar']]
    with prefix("export HADOOP_USER_CLASSPATH_FIRST=true"):
      with prefix("export HADOOP_CLASSPATH=" + ':'.join(hadoop_classpath)):
        #HdfsService().start_if_not_running()
        sudo("hadoop jar {0} {1} {2}".format('libs/user.jar', class_name, arguments), user='hdfs')


def collect_results():
  result_dir = 'testresults/' + datetime.datetime.now().strftime("%Y-%m-%d_%H:%M:%S") + '/%(host)s'
  get('/tmp/lemtest', result_dir)
  run('rm -rf /tmp/lemtest')


class HdfsService(object):
  def is_running(self):
    return is_running("NameNode")
  
  def start(self):
    sudo("start-dfs.sh", user="hadoop")

  def start_if_not_running(self):
    if not self.is_running():
      self.start()

def is_running(hadoop_service):
  result = sudo("jps -lm | grep {0}".format(hadoop_service), warn_only=True)
  return result.succeeded

def put_if_absent(local_paths, remote_dir):
  """Sends artifacts to remote location only if they don't already exist there

  Arguments:
  local_paths - python list of paths that should be uploaded
  remote_dir
  """
  local_artifacts = dict((os.path.basename(local_path), local_path) for local_path in local_paths)
  remote_artifacts = list_dir(remote_dir)
  absent_artifacts = set(local_artifacts.keys()) - set(remote_artifacts)
  for absent_artifact in absent_artifacts:
    put(local_artifacts[absent_artifact], remote_dir)


def list_dir(path):
  """Return content of remote path"""
  output = run('ls ' + path)
  return output.split()


def get_master_ip(properties_file):
  """Given properties file find external ip of cluster master"""
  instances = get_instances(properties_file)
  masters = [x for x in instances if 'jobtracker' in x.roles]
  print masters[0].external_ip


def get_instances(properties_file):
  """Returns list of all instances in the cluster specified by properties_file"""
  result = local(
    "whirr list-cluster --config={0} --quiet".format(properties_file),
    capture=True)
  instances = []
  for line in result.split('\n'):
    instance_info = line.split('\t')
    instance = Instance()
    instance.identity = instance_info[0]
    instance.ami = instance_info[1]
    instance.external_ip = instance_info[2]
    instance.internal_ip = instance_info[3]
    instance.state = instance_info[4]
    instance.zone = instance_info[5]
    instance.roles = instance_info[6]
    instances.append(instance)
  return instances


class Instance:
  pass
