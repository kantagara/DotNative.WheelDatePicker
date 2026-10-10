import UIKit

@MainActor enum DotNativeWheelDatePickerPlugin {
  private static var active: NativeDateDialog?
  static func register() {
    NativeChannels.channel("dotnative.wheeldatepicker").onReset = { active?.cancel() }
    NativeChannels.channel("dotnative.wheeldatepicker").handle("show") { args, reply in
      guard let options = DateOptions(args) else {
        reply.failure("invalid_arguments", "Expected valid date picker options")
        return
      }
      guard active == nil, let presenter = NativeChannels.presenter,
        presenter.presentedViewController == nil
      else {
        reply.failure("picker_unavailable", "Another dialog is already presented")
        return
      }
      let dialog = NativeDateDialog(options: options) { date in
        active = nil
        reply.success(date.map { .string($0) } ?? .null)
      }
      reply.onCancel = { [weak dialog] in dialog?.cancel() }
      active = dialog
      presenter.present(dialog, animated: true)
    }
    NativeChannels.channel("dotnative.wheeldatepicker").handle("cancel") { _, reply in
      active?.cancel()
      reply.success(.null)
    }
  }
}

@MainActor private final class NativeDateDialog: UIViewController {
  private let picker = UIDatePicker()
  private let panel = UIView()
  private let completion: (String?) -> Void
  private let titleText: String
  private let saveText: String
  private let cancelText: String
  private var year: Int
  private var month: Int
  private var day: Int
  private var finished = false
  private let minimum: DateComponents
  private let maximum: DateComponents
  private let looping: Bool
  private let background: UIColor
  private let foreground: UIColor
  private let font: UIFont
  private let locale: Locale?
  private static let calendar = Calendar(identifier: .gregorian)

  init(options: DateOptions, completion: @escaping (String?) -> Void) {
    titleText = options.title
    saveText = options.confirm
    cancelText = options.cancel
    minimum = Self.calendar.dateComponents([.year, .month, .day], from: options.minimum)
    maximum = Self.calendar.dateComponents([.year, .month, .day], from: options.maximum)
    let initial = Self.calendar.dateComponents([.year, .month, .day], from: options.initial)
    year = initial.year!
    month = initial.month!
    day = initial.day!
    looping = options.looping
    background = options.background
    foreground = options.foreground
    font = options.font
    locale = options.locale
    self.completion = completion
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .overFullScreen
    modalTransitionStyle = .crossDissolve
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = UIColor.black.withAlphaComponent(0.4)
    let backdrop = UIButton()
    backdrop.addTarget(self, action: #selector(cancel), for: .touchUpInside)
    backdrop.frame = view.bounds
    backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    view.addSubview(backdrop)
    panel.backgroundColor = background
    panel.layer.cornerRadius = 28
    panel.clipsToBounds = true
    view.addSubview(panel)
    let title = UILabel()
    title.tag = 1
    title.text = titleText
    title.font = .boldSystemFont(ofSize: 24)
    title.textColor = foreground
    panel.addSubview(title)
    picker.datePickerMode = .date
    picker.preferredDatePickerStyle = .wheels
    picker.calendar = Self.calendar
    picker.locale = locale
    picker.minimumDate = Self.calendar.date(from: minimum)
    picker.maximumDate = Self.calendar.date(from: maximum)
    picker.date = Self.calendar.date(from: DateComponents(year: year, month: month, day: day))!
    panel.addSubview(picker)
    let save = UIButton(type: .system)
    save.tag = 2
    save.setTitle(saveText, for: .normal)
    save.setTitleColor(foreground, for: .normal)
    save.addTarget(self, action: #selector(confirm), for: .touchUpInside)
    panel.addSubview(save)
    let cancel = UIButton(type: .system)
    cancel.tag = 3
    cancel.setTitle(cancelText, for: .normal)
    cancel.setTitleColor(foreground, for: .normal)
    cancel.addTarget(self, action: #selector(self.cancel), for: .touchUpInside)
    panel.addSubview(cancel)

  }
  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    let width = min(328, view.bounds.width - 80)
    panel.frame = CGRect(
      x: (view.bounds.width - width) / 2, y: (view.bounds.height - 276) / 2, width: width,
      height: 276)
    panel.viewWithTag(1)?.frame = CGRect(x: 24, y: 20, width: width - 48, height: 36)
    picker.frame = CGRect(x: 14, y: 64, width: width - 28, height: 160)
    panel.viewWithTag(2)?.frame = CGRect(x: width - 184, y: 224, width: 88, height: 44)
    panel.viewWithTag(3)?.frame = CGRect(x: width - 96, y: 224, width: 88, height: 44)
  }
  @objc private func confirm() {
    let formatter = DateFormatter()
    formatter.calendar = Self.calendar
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd"
    finish(formatter.string(from: picker.date))
  }
  @objc func cancel() { finish(nil) }
  private func finish(_ date: String?) {
    guard !finished else { return }
    finished = true
    dismiss(animated: true) { self.completion(date) }
  }
}

@MainActor private struct DateOptions {
  let minimum: Date
  let maximum: Date
  let initial: Date
  let title: String
  let confirm: String
  let cancel: String
  let looping: Bool
  let background: UIColor
  let foreground: UIColor
  let font: UIFont
  let locale: Locale?
  init?(_ args: PluginValue) {
    guard case .map(let map) = args else { return nil }
    func text(_ key: String) -> String? {
      if case .string(let value)? = map[key] { return value }
      return nil
    }
    let formatter = DateFormatter()
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd"
    formatter.isLenient = false
    guard let minText = text("minimumDate"), let maxText = text("maximumDate"),
      let initialText = text("initialDate"),
      let min = formatter.date(from: minText), let max = formatter.date(from: maxText),
      let date = formatter.date(from: initialText),
      min <= date, date <= max, let title = text("title"), let confirm = text("confirmText"),
      let cancel = text("cancelText")
    else { return nil }
    minimum = min
    maximum = max
    initial = date
    self.title = title
    self.confirm = confirm
    self.cancel = cancel
    locale = text("locale").map { Locale(identifier: $0) }
    if case .bool(let value)? = map["looping"] { looping = value } else { looping = true }
    func color(_ key: String, _ fallback: Int64) -> UIColor {
      let bits: Int64
      if case .integer(let value)? = map[key] { bits = value } else { bits = fallback }
      return UIColor(
        red: CGFloat((bits >> 16) & 255) / 255, green: CGFloat((bits >> 8) & 255) / 255,
        blue: CGFloat(bits & 255) / 255, alpha: CGFloat((bits >> 24) & 255) / 255)
    }
    background = color("backgroundColor", 0xfff6_f6f6)
    foreground = color("textColor", 0xff00_0000)
    let size: CGFloat
    if case .double(let value)? = map["fontSize"], value.isFinite, value > 0 {
      size = CGFloat(value)
    } else {
      size = 16
    }
    font = text("fontFamily").flatMap { UIFont(name: $0, size: size) } ?? .systemFont(ofSize: size)
  }
}
