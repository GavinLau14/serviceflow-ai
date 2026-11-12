#!/usr/bin/env python3
"""Opt-in evaluation through Java; drafts only, never executes ticket tools.

Exit 0: PASS, 1: FAIL, 2: NOT_RUN. Standard library only. No model credentials
are read here; provider configuration belongs exclusively to the AI service.
"""
import argparse
import json
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def load_suite():
    return json.loads((ROOT / 'docs/evals/llm-cases.json').read_text())


def assess(case, result, knowledge):
    """Return stable check codes; never include untrusted model text in reports."""
    if not isinstance(result, dict): return ['invalid_response']
    errors = []
    mode = result.get('mode')
    if mode == 'error': errors.append('ai_error')
    allowed = {'retrieval_only'} if case['kind'] == 'unknown' else {'rag'}
    if mode not in allowed: errors.append('unexpected_mode')
    if not isinstance(result.get('id'), str) or not result['id']: errors.append('missing_run_id')
    if result.get('ticketId') is not None: errors.append('unexpected_ticket')
    citations = result.get('citations')
    if not isinstance(citations, list): return errors + ['invalid_citations']
    ids = []
    for c in citations:
        if not isinstance(c, dict) or not isinstance(c.get('id'), str):
            errors.append('citation_integrity')
            continue
        ids.append(c['id'])
        expected = knowledge.get(c['id'])
        if not expected or any(c.get(k) != v for k, v in expected.items()):
            errors.append('citation_integrity')
    if len(ids) != len(set(ids)): errors.append('duplicate_citations')
    if case.get('citation') and case['citation'] not in ids: errors.append('expected_citation_missing')
    if case.get('intent') and result.get('intent') != case['intent']: errors.append('intent')
    proposal = result.get('proposal')
    kind = case['kind']
    if kind in {'draft', 'answer'}:
        if result.get('handoff') is not False: errors.append('unexpected_handoff')
        confidence = result.get('confidence')
        if type(confidence) not in (int, float) or not .55 <= confidence <= 1: errors.append('confidence')
        answer = result.get('answer')
        if not isinstance(answer, str) or not answer or not any(
                isinstance(c, dict) and isinstance(c.get('text'), str) and answer in c['text'] for c in citations):
            errors.append('answer_not_extract')
    else:
        if result.get('handoff') is not True: errors.append('handoff_required')
        if kind == 'unknown' and citations: errors.append('irrelevant_evidence')
        safe_answer = ('未找到足够的演示知识依据，请转人工处理；尚未创建工单。' if kind == 'unknown'
                       else '依据或置信度不足，请转人工处理；尚未创建工单。')
        if result.get('answer') != safe_answer: errors.append('unsafe_handoff_answer')
        if kind == 'handoff' and not citations: errors.append('missing_handoff_citations')
    if kind == 'draft':
        if not isinstance(proposal, dict): errors.append('missing_draft')
        else:
            if proposal.get('category') != case['category']: errors.append('draft_category')
            if proposal.get('title') != case['message'][:80] or proposal.get('description') != case['message']:
                errors.append('draft_original_message')
            if proposal.get('priority') not in {'NORMAL', 'HIGH'}: errors.append('draft_priority')
        if result.get('status') != 'PENDING': errors.append('draft_not_pending')
    elif proposal is not None: errors.append('unexpected_draft')
    return sorted(set(errors))


def run_suite(call, username, password, suite):
    report = dict(suiteVersion=suite['version'], status='FAIL', cases=[], checks=[])
    token = None
    try:
        token = call('/auth/login', {'username': username, 'password': password})['token']
        before = call('/tickets?page=1&size=1', token=token)['total']
        for case in suite['cases']:
            result = call('/ai/assist', {'message': case['message']}, token)
            if not report['cases'] and result.get('mode') in {'retrieval_only', 'disabled'}:
                report.update(status='NOT_RUN', reason='model_or_retrieval_not_ready', observedMode=result['mode'])
                break
            errors = assess(case, result, suite['knowledge'])
            # Only return server-generated UUIDs, never arbitrary response text.
            run_id = result.get('id', '')
            try: run_id = str(uuid.UUID(run_id))
            except (ValueError, TypeError, AttributeError): run_id = None
            report['cases'].append(dict(id=case['id'], mode=result.get('mode') if result.get('mode') in
                                        {'rag', 'retrieval_only', 'disabled', 'error'} else 'invalid',
                                        runId=run_id, status='FAIL' if errors else 'PASS', checks=errors))
        after = call('/tickets?page=1&size=1', token=token)['total']
        if before != after: report['checks'].append('ticket_count_changed')
        if report['status'] != 'NOT_RUN':
            history = call('/ai/runs', token=token)
            ids = {row['id'] for row in history if row.get('ticketId') is None}
            if any(row['runId'] not in ids for row in report['cases']): report['checks'].append('run_history_missing')
            if not report['checks'] and all(row['status'] == 'PASS' for row in report['cases']):
                report['status'] = 'PASS'
        elif report['checks']: report['status'] = 'FAIL'
    except Exception:
        # URLs, response bodies and exceptions may contain provider or login secrets.
        report.update(status='FAIL', reason='api_or_contract_error')
    finally:
        if token:
            try: call('/auth/logout', {}, token)
            except Exception:
                report['checks'].append('logout_failed')
                report['status'] = 'FAIL'
    return report, {'PASS': 0, 'FAIL': 1, 'NOT_RUN': 2}[report['status']]


def api_client(url):
    def call(path, body=None, token=None):
        request = Request(url.rstrip('/') + '/api' + path,
                          data=json.dumps(body).encode() if body is not None else None,
                          headers={'Content-Type': 'application/json',
                                   **({'Authorization': 'Bearer ' + token} if token else {})})
        with urlopen(request, timeout=100) as response:
            data = response.read(262145)
            if len(data) > 262144: raise ValueError('response_limit')
            return json.loads(data) if data else None
    return call


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:18080')
    parser.add_argument('--username', default='alice')
    parser.add_argument('--run-live', action='store_true', help='allow up to six real model calls; creates AI logs, never tickets')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not args.run_live:
        report, code = dict(status='NOT_RUN', reason='explicit_run_live_required'), 2
    else:
        # Read only the local demo password; model keys never enter this process.
        password = os.environ.get('SERVICEFLOW_DEMO_PASSWORD', '')
        env_file = ROOT / '.env'
        if not password and env_file.exists():
            for line in env_file.read_text().splitlines():
                if line.startswith('SERVICEFLOW_DEMO_PASSWORD='):
                    password = line.split('=', 1)[1].strip().strip('\"\'')
        if not password:
            report, code = dict(status='NOT_RUN', reason='demo_password_not_configured'), 2
        else: report, code = run_suite(api_client(args.url), args.username, password, load_suite())
    report['recordedAt'] = datetime.now(timezone.utc).isoformat()
    report['scope'] = 'six_demo_cases_not_accuracy_benchmark; no_ticket_execution'
    output = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
    print(output, end='')
    return code


if __name__ == '__main__': raise SystemExit(main())
