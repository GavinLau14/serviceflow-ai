"""Offline test doubles validate the harness, never remote model quality."""
import sys
import unittest
import uuid
import ast
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from llm_acceptance import load_suite, assess, run_suite

SUITE = load_suite()


def response(case):
    citation = next(iter(SUITE['knowledge'].values()))
    cid = next(iter(SUITE['knowledge']))
    if case.get('citation'):
        cid = case['citation']
        citation = SUITE['knowledge'][cid]
    result = dict(id=str(uuid.uuid5(uuid.NAMESPACE_URL, case['id'])), mode='rag', intent=case.get('intent', 'UNKNOWN'),
                  answer=citation['text'], confidence=.9, handoff=False,
                  citations=[dict(id=cid, **citation)], proposal=None,
                  status='NO_ACTION', ticketId=None)
    if case['kind'] == 'draft':
        result['proposal'] = dict(title=case['message'][:80], description=case['message'],
                                  category=case['category'], priority='NORMAL')
        result['status'] = 'PENDING'
    if case['kind'] == 'handoff':
        result.update(handoff=True, confidence=0, answer='依据或置信度不足，请转人工处理；尚未创建工单。')
    if case['kind'] == 'unknown':
        result.update(mode='retrieval_only', handoff=True, confidence=0,
                      answer='未找到足够的演示知识依据，请转人工处理；尚未创建工单。', citations=[])
    return result


class FakeApi:
    def __init__(self, mode=None, changed_count=False):
        self.mode, self.changed_count = mode, changed_count
        self.calls, self.results = [], []

    def __call__(self, path, body=None, token=None):
        self.calls.append(path)
        if path == '/auth/login': return {'token': 'must-never-appear'}
        if path == '/auth/logout': return None
        if path.startswith('/tickets?'):
            return {'total': 10 + int(self.changed_count and bool(self.results))}
        if path == '/ai/assist':
            case = next(c for c in SUITE['cases'] if c['message'] == body['message'])
            r = response(case)
            if self.mode:
                r.update(mode=self.mode, proposal=None, handoff=True)
            self.results.append(r)
            return r
        if path == '/ai/runs': return self.results
        raise AssertionError('Unexpected endpoint')


class AcceptanceTests(unittest.TestCase):
    def test_knowledge_baseline_matches_bundled_demo_corpus(self):
        source = Path(__file__).resolve().parents[2] / 'ai-service/serviceflow_ai/retrieval.py'
        tree = ast.parse(source.read_text())
        rows = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
                    and any(isinstance(t, ast.Name) and t.id == 'DEMO_KB' for t in n.targets))
        self.assertEqual(SUITE['knowledge'], {r['id']: {k: v for k, v in r.items() if k != 'id'} for r in rows})

    def test_fixture_responses_pass_every_case(self):
        for case in SUITE['cases']:
            with self.subTest(case=case['id']):
                self.assertEqual(assess(case, response(case), SUITE['knowledge']), [])

    def test_wrong_category_and_modified_original_fail(self):
        case = SUITE['cases'][0]
        r = response(case)
        r['proposal'].update(category='DEVICE', description='invented')
        errors = assess(case, r, SUITE['knowledge'])
        self.assertIn('draft_category', errors)
        self.assertIn('draft_original_message', errors)

    def test_fake_reference_and_unsupported_answer_fail(self):
        case = SUITE['cases'][0]
        r = response(case)
        r['citations'][0]['text'] = '保证免费，十分钟上门。'
        r['answer'] = r['citations'][0]['text']
        self.assertIn('citation_integrity', assess(case, r, SUITE['knowledge']))
        r = response(case)
        r['answer'] = '保证免费，十分钟上门。'
        self.assertIn('answer_not_extract', assess(case, r, SUITE['knowledge']))

    def test_handoff_cannot_return_an_actionable_draft(self):
        case = next(c for c in SUITE['cases'] if c['kind'] == 'handoff')
        r = response(case)
        r['proposal'] = response(SUITE['cases'][0])['proposal']
        self.assertIn('unexpected_draft', assess(case, r, SUITE['knowledge']))

    def test_handoff_cannot_hide_a_fabricated_policy_in_answer(self):
        case = next(c for c in SUITE['cases'] if c['kind'] == 'handoff')
        r = response(case)
        r['answer'] = '保证免费，十分钟上门。'
        self.assertIn('unsafe_handoff_answer', assess(case, r, SUITE['knowledge']))

    def test_errors_are_failures_not_quality_passes(self):
        for case in SUITE['cases']:
            r = response(case)
            r.update(mode='error', handoff=True, proposal=None)
            self.assertIn('ai_error', assess(case, r, SUITE['knowledge']))

    def test_malformed_result_fails_without_crashing(self):
        for value in [None, {}, {'citations': None}, {'mode': 'rag', 'citations': [None]}]:
            self.assertTrue(assess(SUITE['cases'][0], value, SUITE['knowledge']))

    def test_no_key_means_not_run_and_stops_after_first_assist(self):
        api = FakeApi(mode='retrieval_only')
        report, code = run_suite(api, 'alice', 'local-secret', SUITE)
        self.assertEqual((report['status'], code), ('NOT_RUN', 2))
        self.assertEqual(report['observedMode'], 'retrieval_only')
        self.assertEqual(api.calls.count('/ai/assist'), 1)
        self.assertEqual(api.calls[-1], '/auth/logout')

    def test_harness_uses_no_ticket_write_endpoint(self):
        api = FakeApi()
        report, code = run_suite(api, 'alice', 'local-secret', SUITE)
        self.assertEqual((report['status'], code), ('PASS', 0))
        self.assertEqual(len(report['cases']), 6)
        self.assertEqual(set(api.calls), {'/auth/login', '/auth/logout', '/tickets?page=1&size=1', '/ai/assist', '/ai/runs'})
        self.assertNotIn('must-never-appear', str(report))
        self.assertNotIn('local-secret', str(report))

    def test_business_writes_or_missing_run_history_fail(self):
        report, code = run_suite(FakeApi(changed_count=True), 'alice', 'secret', SUITE)
        self.assertEqual(code, 1)
        self.assertIn('ticket_count_changed', report['checks'])

    def test_missing_history_fails_even_if_model_responses_pass(self):
        api = FakeApi()
        def missing_history(path, body=None, token=None):
            if path == '/ai/runs': return []
            return api(path, body, token)
        report, code = run_suite(missing_history, 'alice', 'secret', SUITE)
        self.assertEqual(code, 1)
        self.assertIn('run_history_missing', report['checks'])

    def test_upstream_errors_do_not_leak_secrets(self):
        def broken(*args, **kwargs): raise RuntimeError('Bearer real-secret https://private-provider')
        report, code = run_suite(broken, 'alice', 'secret', SUITE)
        self.assertEqual(code, 1)
        self.assertNotIn('real-secret', str(report))


if __name__ == '__main__': unittest.main()
