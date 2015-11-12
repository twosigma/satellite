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
