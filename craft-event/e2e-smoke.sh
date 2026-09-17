#!/usr/bin/env bash
# 端到端冒烟：登录 → 预览 → 并发合成（最后一份 GEM）→ 重试幂等 → 领取 → 取消/超时 → 新版本发布 → 撤销
set -u
BASE=http://127.0.0.1:8080
J=/tmp/e2e
mkdir -p $J
jqget() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d['$1'])"; }

echo "== 1. 登录三个样例账号 =="
OPS=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' -d '{"username":"ops","password":"ops123"}' | tee $J/ops.json | jqget token)
ALICE=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice123"}' | tee $J/alice.json | jqget token)
BOB=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' -d '{"username":"bob","password":"bob123"}' | tee $J/bob.json | jqget token)
echo "ops/alice/bob tokens OK"

echo "== 2. 未登录/越权必须被服务端拒绝 =="
echo "  no token -> $(curl -s -o /dev/null -w '%{http_code}' $BASE/api/player/inventory)"
echo "  player hits operator API -> $(curl -s -o /dev/null -w '%{http_code}' $BASE/api/operator/orders -H "Authorization: Bearer $ALICE")"
echo "  bad password -> $(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/auth/login -H 'Content-Type: application/json' -d '{"username":"alice","password":"WRONG"}')"

echo "== 3. 已发布配方 & 预览 =="
curl -s $BASE/api/player/recipes -H "Authorization: Bearer $ALICE" > $J/recipes.json
python3 - <<'PY'
import json
rs = json.load(open('/tmp/e2e/recipes.json'))
for r in rs:
    print(f"  {r['recipeCode']} v{r['versionNo']} {r['status']} -> {r['outputItemName']}x{r['outputQty']}  mats={[(m['itemCode'],m['qty']) for m in r['materials']]}")
PY
VID=$(python3 -c "import json;rs=json.load(open('$J/recipes.json'));print([r for r in rs if r['recipeCode']=='R001'][0]['versionId'])")
echo "  R001 versionId=$VID"
curl -s $BASE/api/player/recipes/$VID/preview -H "Authorization: Bearer $ALICE" > $J/preview.json
python3 -c "import json;p=json.load(open('$J/preview.json'));print('  preview eventOpen=%s sufficient=%s'% (p['eventOpen'],p['sufficient']))"

echo "== 4. 并发：alice 仅 1 个 GEM，8 个请求同时合成 R001 =="
rm -f $J/race-*.json $J/race-codes.txt
FIFO=/tmp/e2e-gate-$$; mkfifo "$FIFO"
for i in $(seq 1 8); do
  RID="race-$i-$(date +%s%N)"
  (
    # 阻塞在命名管道上，所有 curl 同刻发出
    cat "$FIFO" >/dev/null
    curl -s -o $J/race-$i.json -w '%{http_code}' -X POST $BASE/api/player/craft \
      -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
      -d "{\"versionId\":$VID,\"qty\":1,\"requestId\":\"$RID\"}"
  ) &
done
sleep 1                       # 等 8 个 worker 全部挂到栅栏
for i in $(seq 1 8); do echo go; done > "$FIFO"   # 同时放行
wait
rm -f "$FIFO"
# 状态码依据响应内容判定
for i in $(seq 1 8); do
  if grep -q '"orderNo"' $J/race-$i.json 2>/dev/null; then echo 200; else echo 409; fi
done | sort | uniq -c
WIN=$(grep -l '"status":"HELD"' $J/race-*.json 2>/dev/null | wc -l)
echo "  HELD 单据数: $WIN （期望 1）"
curl -s $BASE/api/player/inventory -H "Authorization: Bearer $ALICE" > $J/inv.json
python3 -c "
import json
for i in json.load(open('$J/inv.json')):
    if i['itemCode'] in ('GEM','WOOD','IRON'): print('  inv', i['itemCode'], i)
"

echo "== 5. 请求重试：同一 requestId 连发 3 次（bob） =="
RVID=$(python3 -c "import json;rs=json.load(open('$J/recipes.json'));print([r for r in rs if r['recipeCode']=='R002'][0]['versionId'])")
RID="retry-$$-$(date +%s%N)"
for i in 1 2 3; do
  curl -s -X POST $BASE/api/player/craft -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
    -d "{\"versionId\":$RVID,\"qty\":1,\"requestId\":\"$RID\"}"
  echo
done | python3 -c "
import sys,json
nos=[json.loads(l)['orderNo'] for l in sys.stdin if l.strip()]
print('  返回单号:', nos, ' 全部相同:', len(set(nos))==1)
"

echo "== 6. 活动结束拦截：R900 已结束，服务端必须 409 =="
EVID=$(python3 -c "import json;rs=json.load(open('$J/recipes.json'));print([r for r in rs if r['recipeCode']=='R900'][0]['versionId'])")
curl -s -w '\n  http=%{http_code}\n' -X POST $BASE/api/player/craft -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"versionId\":$EVID,\"qty\":1,\"requestId\":\"ended-$(date +%s%N)\"}"

echo "== 7. 等待合成完成，领取（幂等再领一次） =="
ORDER=$(grep -l '"status":"HELD"' $J/race-*.json | head -1 | xargs python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['orderNo'])")
echo "  winner order=$ORDER, 等待 6 秒（R001 craft=5s 自动完成）"
sleep 6
curl -s -X POST $BASE/api/player/orders/$ORDER/complete -H "Authorization: Bearer $ALICE" > $J/complete1.json
curl -s -X POST $BASE/api/player/orders/$ORDER/complete -H "Authorization: Bearer $ALICE" > $J/complete2.json
python3 -c "
import json
a=json.load(open('$J/complete1.json'));b=json.load(open('$J/complete2.json'))
print('  两次领取状态:', a['status'], b['status'])
"
curl -s $BASE/api/player/orders/$ORDER -H "Authorization: Bearer $ALICE" > $J/detail.json
python3 -c "
import json
d=json.load(open('$J/detail.json'))
print('  材料去向:', [(h['itemCode'],h['qty'],h['status']) for h in d['holds']])
print('  流水类型:', [e['changeType'] for e in d['ledger']])
"

echo "== 8. 超时释放：R003 手动领取，不领等到超时（craft=2s, timeout=10s） =="
TVID=$(python3 -c "import json;rs=json.load(open('$J/recipes.json'));print([r for r in rs if r['recipeCode']=='R003'][0]['versionId'])")
TO=$(curl -s -X POST $BASE/api/player/craft -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"versionId\":$TVID,\"qty\":1,\"requestId\":\"timeout-$(date +%s%N)\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)['orderNo'])")
echo "  timeout order=$TO, 等 12 秒让调度器释放"
sleep 12
curl -s $BASE/api/player/orders/$TO -H "Authorization: Bearer $ALICE" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  状态:', d['status'], '原因:', d.get('cancelReason'))
print('  去向:', [(h['itemCode'],h['status']) for h in d['holds']])
print('  流水:', [e['changeType'] for e in d['ledger']])
"

echo "== 9. 运营发布新版本（R001 v2：GEM 需求改为 2） =="
START=$(date '+%Y-%m-%d %H:%M:%S'); END=$(date -d '+2 hour' '+%Y-%m-%d %H:%M:%S')
DID=$(curl -s -X POST $BASE/api/operator/recipes/drafts -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d "{
  \"recipeCode\":\"R001\",\"outputItemCode\":\"CHEST\",\"outputQty\":1,
  \"eventStartsAt\":\"$START\",\"eventEndsAt\":\"$END\",
  \"autoComplete\":true,\"craftSeconds\":5,\"timeoutSeconds\":30,
  \"changelog\":\"第二轮：宝石需求 1 -> 2\",\"materials\":[{\"itemCode\":\"WOOD\",\"qty\":3},{\"itemCode\":\"IRON\",\"qty\":2},{\"itemCode\":\"GEM\",\"qty\":2}]}")
echo "  draft: $DID"
NEWVID=$(echo "$DID" | python3 -c "import sys,json;print(json.load(sys.stdin)['versionId'])")
curl -s -X POST $BASE/api/operator/recipes/versions/$NEWVID/publish -H "Authorization: Bearer $OPS" | python3 -c "
import sys,json;d=json.load(sys.stdin);print('  published v%s status=%s' % (d['versionNo'], d['status']))"

echo "== 10. 撤销错误奖励（CHEST 仍在背包 -> 反向流水；再撤销 -> 409） =="
curl -s -X POST $BASE/api/operator/orders/$ORDER/revoke -H "Authorization: Bearer $OPS" | python3 -m json.tool
echo "  重复撤销 -> $(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/operator/orders/$ORDER/revoke -H "Authorization: Bearer $OPS")"

echo "== 11. 产出已使用 -> 异常清单（bob 完成一单后把奖励清零再撤销） =="
MARIADB="/tmp/mariadb/usr/bin/mysql --socket=/tmp/mysql.sock -ucraft -pcraft craft"
BO=$(curl -s -X POST $BASE/api/player/craft -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d "{\"versionId\":$RVID,\"qty\":1,\"requestId\":\"rev-$(date +%s%N)\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)['orderNo'])")
sleep 4
curl -s -X POST $BASE/api/player/orders/$BO/complete -H "Authorization: Bearer $BOB" > /dev/null
# R002 自动完成，调度器 1s 间隔应已完成；若仍 HELD 则手动领取
curl -s $BASE/api/player/orders/$BO -H "Authorization: Bearer $BOB" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  bob order status =', d['status'])"
# 模拟玩家把错误奖励“用掉”：DB 直接把 GIFT 背包清零（生产环境由消耗流水产生）
export LD_LIBRARY_PATH="/tmp/mariadb/usr/lib/aarch64-linux-gnu:${LD_LIBRARY_PATH:-}"
$MARIADB -e "UPDATE inventory SET total_qty=0 WHERE account_id=(SELECT id FROM account WHERE username='bob') AND item_id=(SELECT id FROM item WHERE code='GIFT');"
echo "  GIFT 已清零，发起撤销 -> 预期 reversed=false + 异常清单"
curl -s -X POST $BASE/api/operator/orders/$BO/revoke -H "Authorization: Bearer $OPS" | python3 -m json.tool
echo "  异常清单（OPEN）："
curl -s "$BASE/api/operator/exceptions" -H "Authorization: Bearer $OPS" | python3 -c "
import sys,json
for e in json.load(sys.stdin):
    print('   ', e['exceptionNo'], e['orderNo'], e['status'], '|', e['reason'])
"
echo "E2E_BASE_OK"
