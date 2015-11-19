# Ansible deploy script

If you're deploying Satellite to a cluster, you'll find that repeatedly changing
its configuration on all of your Mesos hosts can involve some repetitive busy work.
We've provided some [Ansible](http://www.ansible.com/) playbooks to help you automate these tasks.

## Instructions

1. Create or adjust the Ansible inventory for your Mesos cluster.  You can just list hosts in a flat file (see inventory.sample), or build your inventory dynamically using the more [sophisticated capabilities of Ansible](http://docs.ansible.com/ansible/intro_dynamic_inventory.html).  Either way, you should arrange your inventory such that it contains the groups 'satellite\_masters' and 'satellite\_slaves', which contain the list of hosts to which you intend to deploy satellite\_master and satellite\_slave respectively.

2. Copy vars.yml.sample to vars.yml, and edit it so that the settings are appropriate for your workstation and your servers.

3. Edit the Satellite configuration files which you specified in your vars.yml (we've provided samples).

4. Edit the Reimann configuration file at satellite-master/config/riemann-config.clj, if you'd like to customize the behavior of Riemann on your Satellite masters.

5. cd to the directory containing this file.

6. Run "ansible-playbook -i your_inventory deploy\_masters.yml" to deploy and start satellite\_master on your Mesos master servers.

7. Run "ansible-playbook -i your_inventory deploy\_slaves.yml"  to deploy and start satellite\_slave on your Mesos slave servers.
