import { describe, it, expect } from '@ohos/hypium';
import { describeError } from '../../../main/ets/util/Logger';

interface BusinessErrorLike {
  code: number;
  message: string;
}

interface PlainObject {
  foo: string;
}

interface CircularNode {
  self?: CircularNode;
  name: string;
}

export default function loggerTest(): void {
  describe('Logger', () => {

    it('describeError_null_returns_null_token', 0, () => {
      expect(describeError(null)).assertEqual('null');
    });

    it('describeError_undefined_returns_null_token', 0, () => {
      expect(describeError(undefined)).assertEqual('null');
    });

    it('describeError_BusinessError_extracts_code_and_message', 0, () => {
      const be: BusinessErrorLike = { code: 1700001, message: 'Notification not enable' };
      const out = describeError(be as Object);
      expect(out.indexOf('code=1700001')).assertLarger(-1);
      expect(out.indexOf('msg=Notification not enable')).assertLarger(-1);
    });

    it('describeError_BusinessError_with_blank_message_does_not_throw', 0, () => {
      const be: BusinessErrorLike = { code: 401, message: '' };
      const out = describeError(be as Object);
      expect(out.indexOf('code=401')).assertLarger(-1);
      // Empty message renders as 'msg='.
      expect(out.indexOf('msg=')).assertLarger(-1);
    });

    it('describeError_native_Error_uses_name_colon_message', 0, () => {
      const err = new Error('boom');
      err.name = 'TypeError';
      const out = describeError(err);
      expect(out).assertEqual('TypeError: boom');
    });

    it('describeError_plain_object_falls_back_to_JSON', 0, () => {
      // No `code` field, not an Error instance — should fall through to
      // JSON.stringify branch.
      const obj: PlainObject = { foo: 'bar' };
      const out = describeError(obj as Object);
      expect(out.indexOf('"foo":"bar"')).assertLarger(-1);
    });

    it('describeError_circular_object_falls_back_to_String', 0, () => {
      // JSON.stringify throws on cycles — describeError must catch and
      // fall through to String(e). For a plain object, String(e) is the
      // canonical "[object Object]".
      const a: CircularNode = { name: 'a' };
      a.self = a;
      const out = describeError(a as Object);
      expect(out).assertEqual('[object Object]');
    });
  });
}
