FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libssl3 ca-certificates libasound2 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
RUN python agent_main.py download-files || true
# agent worker:   docker run --env-file .env IMAGE python agent_main.py start
# token server:   docker run --env-file .env -p 8000:8000 IMAGE uvicorn server.app:app --host 0.0.0.0 --port 8000
CMD ["python", "agent_main.py", "start"]
