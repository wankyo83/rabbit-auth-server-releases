FROM mcr.microsoft.com/playwright:v1.62.0-noble
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb x11vnc novnc websockify openbox x11-utils \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN npm ci --omit=dev --ignore-scripts && mkdir -p /data && chown pwuser:pwuser /data
COPY src ./src
COPY public ./public
COPY entrypoint.sh ./entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
USER pwuser
ENV DISPLAY=:99 DATA_DIR=/data PORT=9870 HOST=0.0.0.0 NOVNC_DIR=/usr/share/novnc BROWSER_SANDBOX=false
EXPOSE 9870
ENTRYPOINT ["/app/entrypoint.sh"]
