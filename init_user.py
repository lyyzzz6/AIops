import subprocess
import os

# BCrypt hash for password 'admin123'
bcrypt_password = "$2a$10$uvYzCf2IDaHGGB3XsApA5OtuSa6LoLrzlfD0aMumWRCmVfRREWx/O"

sql_commands = f"""
USE netdata_ops;
DELETE FROM sys_user WHERE username='admin';
INSERT INTO sys_user (username, password, nickname, status, created_at) VALUES ('admin', '{bcrypt_password}', '系统管理员', 1, NOW());
"""

# Write to temp file
with open('temp_init.sql', 'w') as f:
    f.write(sql_commands)

# Execute
result = subprocess.run(
    ['docker', 'exec', '-i', 'netdata-ops-mysql', 'mysql', '-u', 'root', '-proot123456'],
    input=sql_commands,
    text=True,
    capture_output=True
)

print("STDOUT:", result.stdout)
print("STDERR:", result.stderr)
print("Return code:", result.returncode)

# Clean up
if os.path.exists('temp_init.sql'):
    os.remove('temp_init.sql')
