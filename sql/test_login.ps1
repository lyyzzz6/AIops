$body = '{"username":"admin","password":"admin123"}'
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/auth/login' -Method POST -ContentType 'application/json' -Body $body
