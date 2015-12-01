# Ansible deploy script

If you're deploying Satellite to a cluster, you'll find that repeatedly changing its configuration on all of your Mesos hosts can involve some repetitive busy work.
We've provided some [Ansible](http://www.ansible.com/) Roles to help you automate these tasks.

These Roles deploy satellite-master and satellite-slave.
systemd is used to launch and supervise the respective satellite processes.
Execute either Role to deploy Satellite initially, OR to apply your latest configuration to an existing installation of Satellite.


## Instructions

1. Create or adjust the Ansible inventory for your Mesos cluster.
You can just list hosts in a flat file (see inventory.sample), or build your inventory dynamically using the more [sophisticated capabilities of Ansible](http://docs.ansible.com/ansible/intro_dynamic_inventory.html).
Either way, you should arrange your inventory such that it contains distinct groups, e.g. 'satellite\_masters' and 'satellite\_slaves', which contain the list of hosts to which you intend to deploy satellite\_master and satellite\_slave respectively.

2. Edit example_playbook.yml so that the settings are appropriate for your cluster.

3. Edit the Satellite configuration files specified by the variables in example_playbook.yml.

4. cd to the directory containing this file.

5. Run "ansible-playbook -i your_inventory example_playbook.yml" to deploy and start satellite\_master on your Mesos master servers, and to deploy and start satellite\_slave on your Mesos slave servers.


## Want to use Supervisord (or another supervision solution) instead of Systemd?

If you don't want to use systemd, when running the satellite\_master and satellite\_slave Roles, make sure to exclude the tasks that have the tag "systemd", using "--skip-tags systemd".

This repository also includes some Ansible Roles which illustrate how you can supervise Satellite using Supervisord:  satellite\_master\_supervisor and satellite\_slave\_supervisor.
There's also a [simple playbook](example_supervisord_playbook.yml) that runs these roles.

These Supervisor Roles illustrate how you can deploy Satellite and make sure it stays running via Supervisor, but you probably won't be able to use the Roles on your cluster without modification.
The Roles will actually install Supervisor on your hosts;  assuming that you already have installed Supervisor, you'll want to skip the redundant Tasks.
In your Playbook, you also will probably need to override some of the default variables exposed by Ansible's supervisorctl module (described [here](http://docs.ansible.com/ansible/supervisorctl_module.html)), so that you will be able to successfully communicate with your installation of Supervisor.
