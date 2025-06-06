# Check Docker container logs
docker logs springboot-api
docker logs -f springboot-api  # Follow logs in real-time

# Check Nginx logs
sudo tail -f /var/log/nginx/springboot-api-access.log
sudo tail -f /var/log/nginx/springboot-api-error.log

# Check if services are running
sudo systemctl status nginx
docker ps

# Test API locally
curl http://localhost:8080/your-endpoint
curl http://your-server-ip/your-endpoint