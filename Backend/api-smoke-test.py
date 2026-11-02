"""Exercise the isolated OnePlace API stack on ports 18080-18088."""
import json
import base64
import urllib.error
import urllib.request
import uuid


results = []


def request(port, method, path, body=None, token=None, headers=None):
    request_headers = {"Content-Type": "application/json"}
    if token:
        request_headers["Authorization"] = "Bearer " + token
    request_headers.update(headers or {})
    data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", data=data, headers=request_headers, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            raw = response.read()
            if "json" in response.headers.get("Content-Type", ""):
                try:
                    parsed = json.loads(raw) if raw else None
                except (json.JSONDecodeError, UnicodeDecodeError):
                    parsed = raw[:200]
            else:
                parsed = raw
            return response.status, parsed
    except urllib.error.HTTPError as error:
        return error.code, error.read()[:200].decode(errors="replace")
    except (urllib.error.URLError, TimeoutError) as error:
        return 0, str(error)


def check(label, port, method, path, body=None, token=None, expected=200, verify=None, headers=None):
    status, result = request(port, method, path, body, token, headers)
    passed = status == expected and (verify(result) if verify else True)
    results.append((label, passed, status, expected))
    print(f"{'PASS' if passed else 'FAIL'} {label}: HTTP {status}, expected {expected}")
    if not passed:
        print("  response:", str(result)[:200])
    return result if passed else None


def multipart(fields=None, files=None):
    boundary = "oneplace-" + uuid.uuid4().hex
    chunks = []
    for name, value in (fields or {}).items():
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    for name, (filename, content_type, payload) in (files or {}).items():
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\nContent-Type: {content_type}\r\n\r\n'.encode() + payload + b'\r\n')
    chunks.append(f'--{boundary}--\r\n'.encode())
    return b''.join(chunks), {"Content-Type": f"multipart/form-data; boundary={boundary}"}


suffix = uuid.uuid4().hex[:10]


def register(role, port=18081):
    return check(
        f"register {role.lower()}", port, "POST", "/api/auth/register",
        {"name": "API " + role.title(), "email": f"{role.lower()}-{suffix}@test.invalid",
         "role": role, "password": "Password123!"},
        verify=lambda r: isinstance(r, dict) and bool(r.get("token")) and bool(r.get("user", {}).get("id")),
    )


teacher = register("TEACHER")
student = register("STUDENT", 18080)
seller = register("SELLER")
buyer = register("BUYER")
if not all((teacher, student, seller, buyer)):
    raise SystemExit("Cannot continue without four accounts")
t, s, se, b = (x["token"] for x in (teacher, student, seller, buyer))
check("teacher identity", 18081, "GET", "/api/auth/me", token=t,
      verify=lambda r: r["id"] == teacher["user"]["id"])
check("gateway to auth", 18080, "GET", "/api/auth/me", token=t,
      verify=lambda r: r["id"] == teacher["user"]["id"])
check("missing token rejected", 18081, "GET", "/api/auth/me", expected=401)
check("duplicate email rejected", 18081, "POST", "/api/auth/register",
      {"name": "Duplicate", "email": f"teacher-{suffix}@test.invalid", "role": "TEACHER", "password": "Password123!"}, expected=409)
check("login existing account", 18081, "POST", "/api/auth/login",
      {"email": f"teacher-{suffix}@test.invalid", "password": "Password123!"},
      verify=lambda r: bool(r.get("token")))
check("wrong password rejected", 18081, "POST", "/api/auth/login",
      {"email": f"teacher-{suffix}@test.invalid", "password": "wrong"}, expected=401)
check("enable seller workspace", 18081, "POST", "/api/auth/seller", token=b,
      verify=lambda r: r["user"]["seller"] is True)
check("seller flag persisted", 18081, "GET", "/api/auth/me", token=b,
      verify=lambda r: r["seller"] is True)
check("public display name", 18081, "GET", f"/api/auth/users/{teacher['user']['id']}/display-name",
      verify=lambda r: bool(r))
png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lN8AAAAASUVORK5CYII=")
photo_body, photo_headers = multipart(files={"file": ("avatar.png", "image/png", png)})
check("upload profile photo", 18081, "POST", "/api/auth/me/photo", photo_body, t,
      verify=lambda r: r["hasPhoto"] is True, headers=photo_headers)
check("read own profile photo", 18081, "GET", "/api/auth/me/photo", token=t,
      verify=lambda r: isinstance(r, bytes) and r.startswith(b"\xff\xd8"))
check("read member profile photo", 18081, "GET", f"/api/auth/users/{teacher['user']['id']}/photo",
      verify=lambda r: isinstance(r, bytes) and r.startswith(b"\xff\xd8"))
reset = check("forgot password", 18081, "POST", "/api/auth/forgot-password",
              {"email": f"teacher-{suffix}@test.invalid"}, verify=lambda r: bool(r.get("resetToken")))
if reset:
    check("reset password", 18081, "POST", "/api/auth/reset-password",
          {"token": reset["resetToken"], "password": "NewPassword123!"})
    check("new password login", 18081, "POST", "/api/auth/login",
          {"email": f"teacher-{suffix}@test.invalid", "password": "NewPassword123!"},
          verify=lambda r: bool(r.get("token")))
check("activity write", 18081, "POST", "/api/auth/activities", {"action": "Listed a product"}, t)
check("activity persisted", 18081, "GET", "/api/auth/activities", token=t,
      verify=lambda r: any(x["action"] == "Listed a product" for x in r))

module = check("create classroom module", 18084, "POST", "/api/classroom/modules",
               {"title": "API module " + suffix, "subject": "Testing"}, t)
mid = module["id"]
check("module persisted", 18084, "GET", "/api/classroom/modules", token=s,
      verify=lambda r: any(x["id"] == mid for x in r))
check("gateway to classroom", 18080, "GET", "/api/classroom/modules", token=s,
      verify=lambda r: any(x["id"] == mid for x in r))
check("edit module", 18084, "PUT", f"/api/classroom/modules/{mid}",
      {"title": "Updated module " + suffix, "subject": "Testing"}, t,
      verify=lambda r: r["title"].startswith("Updated"))
check("student module write rejected", 18084, "POST", "/api/classroom/modules",
      {"title": "denied", "subject": "Testing"}, s, expected=403)
topic = check("create classroom topic", 18084, "POST", f"/api/classroom/modules/{mid}/topics",
              {"title": "API lesson " + suffix, "description": "test"}, t)
tid = topic["id"]
check("topic persisted", 18084, "GET", f"/api/classroom/modules/{mid}/topics", token=s,
      verify=lambda r: any(x["id"] == tid for x in r))
check("edit topic", 18084, "PUT", f"/api/classroom/topics/{tid}",
      {"title": "Updated lesson " + suffix, "description": "test"}, t,
      verify=lambda r: r["title"].startswith("Updated"))
check("like topic", 18084, "POST", f"/api/classroom/topics/{tid}/like", token=s,
      verify=lambda r: r["likedByCurrentUser"] is True)
check("classroom feed", 18084, "GET", "/api/classroom/feed", token=s,
      verify=lambda r: any(x["id"] == tid and x["authorName"] == "API Teacher" for x in r))
check("classroom lesson imported into feed", 18085, "GET", "/api/feed?page=0&size=50", token=s,
      verify=lambda r: sum(x.get("legacyTopicId") == tid for x in r["items"]) == 1)
check("classroom feed import is idempotent", 18085, "GET", "/api/feed?page=0&size=50", token=s,
      verify=lambda r: sum(x.get("legacyTopicId") == tid for x in r["items"]) == 1)
check("topic comment", 18084, "POST", f"/api/classroom/topics/{tid}/comments",
      {"text": "API comment", "parentId": None, "materialId": None}, s)
check("comment persisted", 18084, "GET", f"/api/classroom/topics/{tid}/comments", token=t,
      verify=lambda r: any(x["text"] == "API comment" for x in r))
note = check("student private note", 18084, "POST", f"/api/classroom/topics/{tid}/notes",
             {"title": "Private", "text": "Persist me"}, s)
check("note persisted", 18084, "GET", f"/api/classroom/topics/{tid}/notes", token=s,
      verify=lambda r: any(x["id"] == note["id"] for x in r))
check("teacher note write rejected", 18084, "POST", f"/api/classroom/topics/{tid}/notes",
      {"title": "denied", "text": "denied"}, t, expected=403)
check("edit private note", 18084, "PUT", f"/api/classroom/notes/{note['id']}",
      {"title": "Updated private", "text": "Persist me"}, s,
      verify=lambda r: r["title"] == "Updated private")
check("delete private note", 18084, "DELETE", f"/api/classroom/notes/{note['id']}", token=s)
check("deleted note absent", 18084, "GET", f"/api/classroom/topics/{tid}/notes", token=s,
      verify=lambda r: all(x["id"] != note["id"] for x in r))
question = check("add classroom quiz question", 18084, "POST", f"/api/classroom/topics/{tid}/quiz/questions",
                 {"question": "3+3?", "options": ["4", "5", "6", "7"], "correctAnswer": 2}, t)
check("student classroom quiz hides answer", 18084, "GET", f"/api/classroom/topics/{tid}/quiz", token=s,
      verify=lambda r: r[0]["correctAnswer"] is None)
check("submit classroom quiz", 18084, "POST", f"/api/classroom/topics/{tid}/quiz/submit",
      {"answers": {question["id"]: 2}}, s, verify=lambda r: r["correct"] == 1)
material = check("create classroom material", 18084, "POST", "/api/classroom/materials",
                 {"title": "API material", "content": "Text"}, t)
check("classroom material persisted", 18084, "GET", "/api/classroom/materials", token=s,
      verify=lambda r: any(x["id"] == material["id"] for x in r))
material_body, material_headers = multipart({"title": "Uploaded material"},
                                            {"file": ("notes.txt", "text/plain", b"material-bytes")})
uploaded_material = check("upload classroom material", 18084, "POST", "/api/classroom/materials/upload",
                          material_body, t, headers=material_headers)
if uploaded_material:
    check("download classroom material", 18084, "GET",
          f"/api/classroom/materials/{uploaded_material['id']}/file", token=s,
          verify=lambda r: r == b"material-bytes")
resource_body, resource_headers = multipart({"title": "API resource", "content": "Text resource"})
resource = check("create topic resource", 18084, "POST", f"/api/classroom/topics/{tid}/resources",
                 resource_body, t, headers=resource_headers)
check("topic resource persisted", 18084, "GET", f"/api/classroom/topics/{tid}/resources", token=s,
      verify=lambda r: any(x["id"] == resource["id"] for x in r))
check("delete topic resource", 18084, "DELETE", f"/api/classroom/topics/{tid}/resources/{resource['id']}", token=t)
check("deleted topic resource absent", 18084, "GET", f"/api/classroom/topics/{tid}/resources", token=s,
      verify=lambda r: all(x["id"] != resource["id"] for x in r))
file_resource_body, file_resource_headers = multipart({"title": "File resource"},
                                                       {"file": ("resource.txt", "text/plain", b"resource-bytes")})
file_resource = check("upload topic resource file", 18084, "POST",
                      f"/api/classroom/topics/{tid}/resources", file_resource_body, t,
                      headers=file_resource_headers)
if file_resource:
    check("download topic resource file", 18084, "GET",
          f"/api/classroom/topics/{tid}/resources/{file_resource['id']}/file", token=s,
          verify=lambda r: r == b"resource-bytes")
video_body, video_headers = multipart(files={"file": ("clip.mp4", "video/mp4", b"api-test-video")})
check("upload classroom video", 18084, "POST", f"/api/classroom/topics/{tid}/video",
      video_body, t, headers=video_headers)
ticket = check("issue classroom video ticket", 18084, "GET", f"/api/classroom/topics/{tid}/video-ticket", token=s)
if ticket:
    check("read classroom video via ticket", 18084, "GET", ticket["url"],
          verify=lambda r: r == b"api-test-video")
check("delete classroom video", 18084, "DELETE", f"/api/classroom/topics/{tid}/video", token=t)
check("deleted video unavailable", 18084, "GET", f"/api/classroom/topics/{tid}/video-ticket", token=s, expected=404)

post = check("feed publish", 18085, "POST", "/api/feed",
             {"title": "API post " + suffix, "body": "Body", "type": "POST"}, t)
check("feed read via gateway", 18080, "GET", f"/api/feed/{post['id']}", token=s,
      verify=lambda r: r["id"] == post["id"])
check("student feed publish rejected", 18085, "POST", "/api/feed", {"title": "denied"}, s, expected=403)
check("feed comment", 18085, "POST", f"/api/feed/{post['id']}/comments", {"text": "Feed comment"}, s)
check("feed comment persisted", 18085, "GET", f"/api/feed/{post['id']}/comments", token=t,
      verify=lambda r: any(x["text"] == "Feed comment" for x in r))
check("feed private note", 18085, "POST", f"/api/feed/{post['id']}/notes", {"text": "Feed note"}, s)
check("feed note persisted", 18085, "GET", f"/api/feed/{post['id']}/notes", token=s,
      verify=lambda r: any(x["text"] == "Feed note" for x in r))
upload_id = str(uuid.uuid4())
check("upload feed media chunk", 18085, "PUT", f"/api/feed/media/uploads/{upload_id}/0",
      b"api-image-bytes", t, headers={"Content-Type": "application/octet-stream"})
check("list feed media chunks", 18085, "GET", f"/api/feed/media/uploads/{upload_id}", token=t,
      verify=lambda r: r == [0])
media = check("complete feed media upload", 18085, "POST", f"/api/feed/media/uploads/{upload_id}/complete",
              {"name": "test.png", "type": "image/png", "size": 15, "chunks": 1}, t)
if media:
    check("read uploaded feed media", 18085, "GET", media["url"],
          verify=lambda r: r == b"api-image-bytes")
direct_media_body, direct_media_headers = multipart(files={"file": ("direct.png", "image/png", png)})
direct_media = check("direct feed media upload", 18085, "POST", "/api/feed/media",
                     direct_media_body, t, headers=direct_media_headers)
if direct_media:
    check("read direct feed media", 18085, "GET", direct_media["url"],
          verify=lambda r: r == png)
draft = check("studio draft", 18086, "POST", "/api/studio",
              {"title": "Studio " + suffix, "body": "Body", "type": "POST"}, t)
check("studio edit", 18086, "PUT", f"/api/studio/{draft['id']}",
      {"title": "Studio " + suffix, "body": "Edited", "type": "POST"}, t,
      verify=lambda r: r["body"] == "Edited")
check("studio draft persisted", 18086, "GET", "/api/studio", token=t,
      verify=lambda r: any(x["id"] == draft["id"] and x["body"] == "Edited" for x in r))
check("gateway to studio", 18080, "GET", "/api/studio", token=t,
      verify=lambda r: any(x["id"] == draft["id"] for x in r))
check("studio to feed publish", 18086, "POST", f"/api/studio/{draft['id']}/publish", token=t)
check("studio publish idempotent", 18086, "POST", f"/api/studio/{draft['id']}/publish", token=t)
check("studio post in feed", 18085, "GET", "/api/feed?page=0&size=50", token=s,
      verify=lambda r: any(x.get("sourceDraftId") == draft["id"] for x in r["items"]))

quiz = check("create quiz", 18087, "POST", "/api/quizzes",
             {"name": "Quiz " + suffix, "module": "API module", "subject": "Testing", "topic": "Lesson",
              "timeMinutes": 15, "questions": [{"question": "2+2?", "options": ["2", "3", "4", "5"], "correctAnswer": 2}]}, t,
             verify=lambda r: isinstance(r, dict) and len(r.get("questions", [])) == 1)
if quiz:
    qid = quiz["id"]
    check("gateway to quiz", 18080, "GET", f"/api/quizzes/{qid}", token=s,
          verify=lambda r: r["id"] == qid)
    linked_post = check("publish quiz link to feed", 18085, "POST", "/api/feed",
                        {"title": "Linked quiz " + suffix, "body": "Take the quiz", "type": "QUIZ", "quizId": str(qid)}, t)
    if linked_post:
        check("student reads linked quiz in feed", 18085, "GET", f"/api/feed/{linked_post['id']}", token=s,
              verify=lambda r: r["quizId"] == str(qid))
    check("student quiz hides answer", 18087, "GET", f"/api/quizzes/{qid}", token=s,
          verify=lambda r: r["questions"][0]["correctAnswer"] is None)
    attempt = check("student starts quiz", 18087, "POST", f"/api/quizzes/{qid}/start", token=s)
    if attempt:
        aid = attempt["attemptId"]
        answer = {str(attempt["quiz"]["questions"][0]["id"]): 2}
        check("student submits quiz", 18087, "POST", f"/api/quizzes/attempts/{aid}/submit",
              {"answers": answer, "warnings": 0}, s, verify=lambda r: r["score"] == 1)
        check("quiz result persisted", 18087, "GET", f"/api/quizzes/attempts/{aid}/result", token=s,
              verify=lambda r: r["score"] == 1)
    warning_attempt = check("start second quiz attempt", 18087, "POST", f"/api/quizzes/{qid}/start", token=s)
    if warning_attempt:
        check("record quiz warning", 18087, "POST",
              f"/api/quizzes/attempts/{warning_attempt['attemptId']}/warning",
              {"answers": {}, "warnings": 1}, s)

product = check("seller creates product", 18082, "POST", "/api/products",
                {"name": "API item " + suffix, "description": "test", "category": "Testing",
                 "price": 10, "currency": "INR", "stock": 5}, se)
if product:
    pid = product["id"]
    check("product read via gateway", 18080, "GET", f"/api/products/{pid}",
          verify=lambda r: r["id"] == pid)
    check("catalog list", 18082, "GET", "/api/products?search=API%20item",
          verify=lambda r: any(x["id"] == pid for x in r))
    check("seller product list", 18082, "GET", "/api/products/mine", token=se,
          verify=lambda r: any(x["id"] == pid for x in r))
    check("edit product", 18082, "PUT", f"/api/products/{pid}",
          {"name": "Updated item " + suffix, "description": "test", "category": "Testing",
           "price": 10, "currency": "INR", "stock": 5}, se,
          verify=lambda r: r["name"].startswith("Updated"))
    product_image_body, product_image_headers = multipart(files={"file": ("product.png", "image/png", png)})
    check("upload product image", 18082, "POST", f"/api/products/{pid}/image",
          product_image_body, se, headers=product_image_headers)
    check("read product image", 18082, "GET", f"/api/products/{pid}/image",
          verify=lambda r: r == png)
    check("inventory rejects wrong service key", 18082, "POST", "/internal/inventory/reserve",
          [{"productId": pid, "quantity": 1}], expected=403,
          headers={"X-Service-Key": "wrong-key"})
    check("buyer adds cart item", 18083, "POST", "/api/cart/items",
          {"productId": pid, "quantity": 1}, b)
    check("merge cart item", 18083, "POST", "/api/cart/merge",
          [{"productId": pid, "quantity": 1}], b,
          verify=lambda r: any(x["productId"] == pid and x["quantity"] == 2 for x in r))
    check("remove cart item", 18083, "DELETE", f"/api/cart/items/{pid}", token=b,
          verify=lambda r: not r)
    check("re-add cart item", 18083, "POST", "/api/cart/items",
          {"productId": pid, "quantity": 1}, b)
    check("cart persisted", 18083, "GET", "/api/cart", token=b,
          verify=lambda r: any(x["productId"] == pid for x in r))
    check("gateway to cart", 18080, "GET", "/api/cart", token=b,
          verify=lambda r: any(x["productId"] == pid for x in r))
    payment = check("cart reserves catalog stock", 18083, "POST", "/api/orders/prepare-payment", token=b)
    if payment:
        order = check("payment sends delivery order", 18083, "POST",
                      f"/api/orders/payments/{payment['id']}/complete", token=b)
        check("cart cleared after payment", 18083, "GET", "/api/cart", token=b,
              verify=lambda r: not r)
        check("stock decreased", 18082, "GET", f"/api/products/{pid}",
              verify=lambda r: r["stock"] == 4)
        if order:
            check("seller sees order", 18083, "GET", "/api/seller/orders", token=se,
                  verify=lambda r: any(x["id"] == order["id"] for x in r))
        if order:
            check("order persisted", 18083, "GET", "/api/orders", token=b,
                  verify=lambda r: any(x["id"] == order["id"] for x in r))
            check("delivery received order", 18088, "GET", f"/api/delivery/orders/{order['id']}", token=b,
                  verify=lambda r: r["orderId"] == order["id"])
            check("gateway to delivery", 18080, "GET", f"/api/delivery/orders/{order['id']}", token=b,
                  verify=lambda r: r["orderId"] == order["id"])
            check("delivery acknowledge", 18088, "POST",
                  f"/api/delivery/orders/{order['id']}/acknowledge", token=b)
            check("delivery rejects wrong service key", 18088, "POST", "/api/delivery/orders",
                  {"orderId": order["id"], "userId": buyer["user"]["id"]}, expected=403,
                  headers={"X-Service-Key": "wrong-key"})
    check("new cart item for cancellation", 18083, "POST", "/api/cart/items",
          {"productId": pid, "quantity": 1}, b)
    cancellation = check("reserve stock for cancellation", 18083, "POST", "/api/orders/prepare-payment", token=b)
    if cancellation:
        check("cancel payment releases stock", 18083, "POST",
              f"/api/orders/payments/{cancellation['id']}/cancel", token=b)
        check("stock restored after cancel", 18082, "GET", f"/api/products/{pid}",
              verify=lambda r: r["stock"] == 4)

check("delete classroom topic", 18084, "DELETE", f"/api/classroom/topics/{tid}", token=t)
check("deleted topic absent", 18084, "GET", f"/api/classroom/modules/{mid}/topics", token=s,
      verify=lambda r: all(x["id"] != tid for x in r))
child = check("create topic for module cascade", 18084, "POST", f"/api/classroom/modules/{mid}/topics",
              {"title": "Cascade lesson " + suffix, "description": "delete with module"}, t)
if child:
    child_id = child["id"]
    check("comment on cascade topic", 18084, "POST", f"/api/classroom/topics/{child_id}/comments",
          {"text": "Remove with module"}, s)
    check("like cascade topic", 18084, "POST", f"/api/classroom/topics/{child_id}/like", token=s)
    check("quiz on cascade topic", 18084, "POST", f"/api/classroom/topics/{child_id}/quiz/questions",
          {"question": "1+1?", "options": ["1", "2", "3", "4"], "correctAnswer": 1}, t)
check("delete classroom module", 18084, "DELETE", f"/api/classroom/modules/{mid}", token=t)
check("deleted module absent", 18084, "GET", "/api/classroom/modules", token=s,
      verify=lambda r: all(x["id"] != mid for x in r))

print(f"SUMMARY: {sum(passed for _, passed, _, _ in results)}/{len(results)} passed")
if product:
    print(f"PERSISTENCE_SENTINEL product_id={product['id']} name={product['name']!r}")
raise SystemExit(0 if all(passed for _, passed, _, _ in results) else 1)
