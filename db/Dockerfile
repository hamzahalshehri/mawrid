# MySQL 8 for Render. Data lives in a sub-folder of the disk mount so the disk's lost+found folder does not break first start.
FROM mysql:8.0
CMD ["mysqld", "--datadir=/var/lib/mysql/data"]
