#!/bin/sh
# M2.3: ES 8 安装 IK 中文分词插件（版本必须与 ES 完全一致）
# 用法: docker exec mall-es /install-ik.sh
set -e
ELASTIC_VERSION=8.11.4
cd /tmp
echo "downloading analysis-ik ${ELASTIC_VERSION} from infinilabs release..."
curl -sL --max-time 300 -o ik.zip "https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-${ELASTIC_VERSION}.zip"
ls -la ik.zip
mkdir -p /usr/share/elasticsearch/plugins/ik
cd /usr/share/elasticsearch/plugins/ik
unzip -oq /tmp/ik.zip
rm -f /tmp/ik.zip
echo "IK installed:"
ls -la
echo "plugin list:"
/usr/share/elasticsearch/bin/elasticsearch-plugin list
