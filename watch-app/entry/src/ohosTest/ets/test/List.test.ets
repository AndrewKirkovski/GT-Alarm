import scaffoldTest from './Scaffold.test';
import alarmItemTest from './AlarmItem.test';
import loggerTest from './Logger.test';
import i18nTest from './I18n.test';
import alarmStoreTest from './AlarmStore.test';
import reminderServiceTest from './ReminderService.test';
import tombstonesTest from './Tombstones.test';

export default function testsuite(): void {
  scaffoldTest();
  alarmItemTest();
  loggerTest();
  i18nTest();
  alarmStoreTest();
  reminderServiceTest();
  tombstonesTest();
}
