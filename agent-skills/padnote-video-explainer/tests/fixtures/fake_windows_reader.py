import base64
import json
import os
import sys
import time

root = os.path.realpath(sys.argv[sys.argv.index('--root') + 1])
hold_open_after_eof = False
for line in sys.stdin:
    try:
        request = json.loads(line)
        path = os.path.join(root, *request['relative_path'].split('/'))
        if request['relative_path'].endswith('bad-response.fixture'):
            sys.stdout.write('{"unexpected":true}\n')
            sys.stdout.flush()
            continue
        if request['relative_path'].endswith('wrong-id.fixture'):
            request['id'] = '00000000-0000-4000-8000-000000000000'
        if request['relative_path'].endswith('timeout.fixture'):
            time.sleep(6)
        if request['relative_path'].endswith('hold-open.fixture'):
            hold_open_after_eof = True
        if request['relative_path'].endswith('exit.fixture'):
            os._exit(9)
        if request['relative_path'].endswith('oversize.fixture'):
            response = {
                'schema_version': 1, 'id': request['id'], 'ok': True, 'mode': request['mode'],
                'data_base64': 'A' * (request['max_bytes'] * 2 + 4),
                'file_size': '1', 'offset': '0', 'dev': '1', 'ino': '2',
                'mtime_ns': '3', 'ctime_ns': '4',
            }
            sys.stdout.write(json.dumps(response, separators=(',', ':')) + '\n')
            sys.stdout.flush()
            continue
        with open(path, 'rb') as stream:
            data = stream.read()
        mode = request['mode']
        limit = request['max_bytes']
        offset = 0 if mode == 'whole' else max(0, len(data) - limit)
        selected = data[offset:]
        response = {
            'schema_version': 1, 'id': request['id'], 'ok': True, 'mode': mode,
            'data_base64': base64.b64encode(selected).decode('ascii'),
            'file_size': str(len(data)), 'offset': str(offset),
            'dev': '1', 'ino': '2', 'mtime_ns': '3', 'ctime_ns': '4',
        }
        sys.stdout.write(json.dumps(response, separators=(',', ':')) + '\n')
        sys.stdout.flush()
        if request['relative_path'].endswith('delayed-extra.fixture'):
            time.sleep(0.2)
            sys.stdout.write('{}\n')
            sys.stdout.flush()
    except FileNotFoundError:
        response = {'schema_version': 1, 'id': request['id'], 'ok': False, 'code': 'not_found'}
        sys.stdout.write(json.dumps(response, separators=(',', ':')) + '\n')
        sys.stdout.flush()
    except Exception:
        response = {'schema_version': 1, 'id': request['id'], 'ok': False, 'code': 'read_failed'}
        sys.stdout.write(json.dumps(response, separators=(',', ':')) + '\n')
        sys.stdout.flush()
if hold_open_after_eof:
    time.sleep(2.5)
