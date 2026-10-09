import CoreText
import CryptoKit
import UIKit

@MainActor enum DotNativeHoloDatePickerPlugin {
  private static var active: HoloDateDialog?
  private static var fontNames: [String: String] = [:]
  private static var fontBuffers: [String: (Data, Int, Int)] = [:]
  static func font(_ id: String, size: CGFloat) -> UIFont? {
    fontNames[id].flatMap { UIFont(name: $0, size: size) }
  }
  static func register() {
    NativeChannels.channel("dotnative.holodatepicker").handle("fontChunk") { args, reply in
      guard case .map(let map) = args, case .string(let id)? = map["id"], id.count == 64,
        case .integer(let index)? = map["index"], case .integer(let total)? = map["total"],
        total > 0, total <= 16, index >= 0, index < total, case .bytes(let chunk)? = map["data"],
        chunk.count <= 512 * 1024
      else {
        reply.failure("invalid_font", "Invalid font chunk")
        return
      }
      if index == 0 { fontBuffers[id] = (Data(), 0, Int(total)) }
      guard var buffer = fontBuffers[id], buffer.1 == Int(index), buffer.2 == Int(total) else {
        reply.failure("invalid_font", "Font chunks must arrive in order")
        return
      }
      buffer.0.append(chunk)
      buffer.1 += 1
      if buffer.1 == buffer.2 {
        fontBuffers.removeValue(forKey: id)
        let hash = SHA256.hash(data: buffer.0).map { String(format: "%02x", $0) }.joined()
        guard hash == id, let provider = CGDataProvider(data: buffer.0 as CFData),
          let font = CGFont(provider), let name = font.postScriptName
        else {
          reply.failure("invalid_font", "Font data is invalid")
          return
        }
        CTFontManagerRegisterGraphicsFont(font, nil)
        fontNames[id] = name as String
      } else {
        fontBuffers[id] = buffer
      }
      reply.success(.null)
    }
    NativeChannels.channel("dotnative.holodatepicker").handle("show") { args, reply in
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
      let dialog = HoloDateDialog(options: options) { date in
        active = nil
        reply.success(date.map { .string($0) } ?? .null)
      }
      active = dialog
      presenter.present(dialog, animated: false) {
        dialog.view.alpha = 0
        UIView.animate(withDuration: 0.15, delay: 0, options: .curveEaseOut) {
          dialog.view.alpha = 1
        }
      }
    }
    NativeChannels.channel("dotnative.holodatepicker").handle("cancel") { _, reply in
      active?.cancel()
      reply.success(.null)
    }
  }
}

@MainActor
private final class HoloDateDialog: UIViewController {
  private let wheels = [HoloWheel(), HoloWheel(), HoloWheel()]
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
  private let titleFont: UIFont
  private let actionFont: UIFont
  private let itemColor: UIColor
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
    titleFont = options.titleFont
    actionFont = options.actionFont
    itemColor = options.itemColor
    self.completion = completion
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .overFullScreen
    modalTransitionStyle = .crossDissolve
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = UIColor.black.withAlphaComponent(0.54)
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
    title.font = titleFont
    title.textColor = foreground
    panel.addSubview(title)
    for (component, wheel) in wheels.enumerated() {
      panel.addSubview(wheel)
      wheel.accessibilityLabel = ["Day", "Month", "Year"][component]
      wheel.changed = { [weak self] value in self?.select(value, component: component) }
    }
    let save = UIButton(type: .system)
    save.tag = 2
    save.titleLabel?.font = actionFont
    save.setTitle(saveText, for: .normal)
    save.setTitleColor(foreground, for: .normal)
    save.addTarget(self, action: #selector(confirm), for: .touchUpInside)
    panel.addSubview(save)
    let cancel = UIButton(type: .system)
    cancel.tag = 3
    cancel.titleLabel?.font = actionFont
    cancel.setTitle(cancelText, for: .normal)
    cancel.setTitleColor(foreground, for: .normal)
    cancel.addTarget(self, action: #selector(self.cancel), for: .touchUpInside)
    panel.addSubview(cancel)
    selectRows()
  }
  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    let width = min(328, view.bounds.width - 80)
    let titleHeight = ceil(titleFont.pointSize * 4 / 3)
    let height = 24 + titleHeight + 160 + 72
    let safe = view.safeAreaInsets
    panel.frame = CGRect(
      x: (view.bounds.width - width) / 2,
      y: safe.top + (view.bounds.height - safe.top - safe.bottom - height) / 2, width: width,
      height: height)
    panel.viewWithTag(1)?.frame = CGRect(x: 24, y: 24, width: width - 48, height: titleHeight)
    let columnWidth = (width - 28) / 3
    for (component, wheel) in wheels.enumerated() {
      wheel.frame = CGRect(
        x: 14 + CGFloat(component) * columnWidth, y: 24 + titleHeight, width: columnWidth,
        height: 160)
    }
    let saveWidth = max(
      64, (saveText as NSString).size(withAttributes: [.font: actionFont]).width + 24)
    let cancelWidth = max(
      64, (cancelText as NSString).size(withAttributes: [.font: actionFont]).width + 24)
    panel.viewWithTag(2)?.frame = CGRect(
      x: width - 24 - cancelWidth - 8 - saveWidth, y: height - 72, width: saveWidth, height: 48)
    panel.viewWithTag(3)?.frame = CGRect(
      x: width - 24 - cancelWidth, y: height - 72, width: cancelWidth, height: 48)
  }
  private func lower(_ component: Int) -> Int {
    if component == 2 { return minimum.year! }
    if component == 1 { return year == minimum.year! ? minimum.month! : 1 }
    return year == minimum.year! && month == minimum.month! ? minimum.day! : 1
  }
  private func upper(_ component: Int) -> Int {
    if component == 2 { return maximum.year! }
    if component == 1 { return year == maximum.year! ? maximum.month! : 12 }
    if year == maximum.year! && month == maximum.month! { return maximum.day! }
    let date = Self.calendar.date(from: DateComponents(year: year, month: month, day: 1))!
    return Self.calendar.range(of: .day, in: .month, for: date)!.count
  }
  private func count(_ component: Int) -> Int { upper(component) - lower(component) + 1 }
  private func selectRows() {
    for component in 0..<3 {
      wheels[component].configure(
        lower: lower(component), upper: upper(component),
        selected: component == 2 ? year : component == 1 ? month : day,
        looping: looping, year: component == 2, font: font, ink: itemColor)
    }
  }
  private func select(_ value: Int, component: Int) {
    if component == 2 { year = value } else if component == 1 { month = value } else { day = value }
    month = max(lower(1), min(month, upper(1)))
    day = max(lower(0), min(day, upper(0)))
    // Leave the active wheel's scroll velocity intact; update dependent ranges only.
    if component == 2 {
      wheels[1].configure(
        lower: lower(1), upper: upper(1), selected: month, looping: looping, year: false,
        font: font, ink: itemColor)
    }
    if component != 0 {
      wheels[0].configure(
        lower: lower(0), upper: upper(0), selected: day, looping: looping, year: false, font: font,
        ink: itemColor)
    }
  }
  @objc private func confirm() { finish(String(format: "%04d-%02d-%02d", year, month, day)) }
  @objc func cancel() { finish(nil) }
  private func finish(_ date: String?) {
    guard !finished else { return }
    finished = true
    UIView.animate(
      withDuration: 0.15, delay: 0, options: .curveEaseOut,
      animations: {
        self.view.alpha = 0
      }
    ) { _ in self.dismiss(animated: false) { self.completion(date) } }
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
  let titleFont: UIFont
  let actionFont: UIFont
  let itemColor: UIColor
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
    func loadFont(_ key: String, size: CGFloat, fallback: UIFont) -> UIFont {
      if let id = text(key + "Id"), let font = DotNativeHoloDatePickerPlugin.font(id, size: size) {
        return font
      }
      return fallback
    }
    func number(_ key: String, _ fallback: CGFloat) -> CGFloat {
      if case .double(let value)? = map[key], value.isFinite, value > 0 { return CGFloat(value) }
      return fallback
    }
    font = loadFont(
      "fontData", size: size,
      fallback: text("fontFamily").flatMap { UIFont(name: $0, size: size) }
        ?? .systemFont(ofSize: size))
    let titleSize = number("titleFontSize", 24)
    let actionSize = number("actionFontSize", 14)
    titleFont = loadFont(
      "titleFontData", size: titleSize, fallback: .boldSystemFont(ofSize: titleSize))
    actionFont = loadFont(
      "actionFontData", size: actionSize,
      fallback: .systemFont(ofSize: actionSize, weight: .semibold))
    itemColor = color("itemColor", 0xff00_0000)
  }
}
