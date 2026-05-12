/**
 * Shared confirmation dialog for destructive alarm actions. Used from
 * Index (long-press a row) and EditPage (Delete button).
 *
 * `alarmLabel` is shown as a subtitle when non-empty so the user can
 * verify which alarm they're about to delete. Pass empty string to skip.
 */
@CustomDialog
export struct DeleteConfirmDialog {
  controller: CustomDialogController;
  // @Prop is required for the dialog to re-read the label when the parent
  // updates pendingDeleteLabel between long-presses on different alarms.
  // Plain fields capture once at controller construction.
  @Prop alarmLabel: string = '';
  onConfirm: () => void = () => {};

  build() {
    Column() {
      Text($r('app.string.delete_dialog_title'))
        .fontSize(14)
        .fontWeight(FontWeight.Bold)
        .fontColor('#FFFFFF')
        .margin({ top: 14, bottom: 4 })
      if (this.alarmLabel.length > 0) {
        Text(this.alarmLabel)
          .fontSize(11)
          .fontColor('#AAAAAA')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ bottom: 10 })
      } else {
        Text($r('app.string.delete_dialog_warning'))
          .fontSize(10)
          .fontColor('#888888')
          .margin({ bottom: 10 })
      }
      Row({ space: 8 }) {
        // 44vp height meets the Wear OS small-screen 40dp floor. The
        // destructive button needs a comfortable hit target so the user
        // doesn't mistap and lose data they didn't mean to delete.
        Button($r('app.string.action_cancel'))
          .fontSize(12)
          .width(86)
          .height(44)
          .backgroundColor('#333333')
          .accessibilityText($r('app.string.a11y_cancel_deletion'))
          .onClick(() => this.controller.close())
        Button($r('app.string.action_delete'))
          .fontSize(12)
          .width(86)
          .height(44)
          .backgroundColor('#AA2222')
          .fontColor('#FFFFFF')
          .accessibilityText($r('app.string.a11y_confirm_delete'))
          .onClick(() => {
            this.controller.close();
            this.onConfirm();
          })
      }
      .margin({ bottom: 14 })
    }
    .width(220)
    .backgroundColor('#1A1A1A')
    .borderRadius(16)
    .alignItems(HorizontalAlign.Center)
    .direction(Direction.Auto)
  }
}
