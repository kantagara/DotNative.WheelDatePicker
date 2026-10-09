import UIKit

// Each wheel uses the reference's 160/36/18 geometry. The center band is flat;
// outer rows use the cylindrical projection, without a system selection overlay.
@MainActor final class HoloWheel: UIView, UIScrollViewDelegate {
  var changed: ((Int) -> Void)?
  private let scroll = UIScrollView()
  private let top = UIView()
  private let centerBand = UIView()
  private let bottom = UIView()
  private let upperLine = UIView()
  private let lowerLine = UIView()
  private var lower = 1
  private var upper = 31
  private var looping = true
  private var year = false
  private var font = UIFont.systemFont(ofSize: 16)
  private var ink = UIColor.black
  private var updating = false
  private var reported = 0
  private var rows: [[UILabel]] = []
  private var origin: CGFloat { looping ? CGFloat(count * 500) : 0 }
  private var count: Int { upper - lower + 1 }
  private var offset: CGFloat { scroll.contentOffset.y / 36 - origin }

  override init(frame: CGRect) {
    super.init(frame: frame)
    for band in [top, centerBand, bottom] {
      band.clipsToBounds = true
      band.isUserInteractionEnabled = false
      addSubview(band)
      var labels: [UILabel] = []
      for _ in 0..<7 {
        let label = UILabel()
        label.textAlignment = .center
        band.addSubview(label)
        labels.append(label)
      }
      rows.append(labels)
    }
    for line in [upperLine, lowerLine] {
      line.isUserInteractionEnabled = false
      addSubview(line)
    }
    scroll.backgroundColor = .clear
    scroll.showsVerticalScrollIndicator = false
    scroll.contentInsetAdjustmentBehavior = .never
    scroll.delegate = self
    addSubview(scroll)
    scroll.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tap(_:))))
    isAccessibilityElement = true
    accessibilityTraits = .adjustable
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  func configure(
    lower: Int, upper: Int, selected: Int, looping: Bool, year: Bool,
    font: UIFont, ink: UIColor
  ) {
    self.lower = lower
    self.upper = upper
    self.looping = looping
    self.year = year
    self.font = font
    self.ink = ink
    updating = true
    scroll.setContentOffset(
      CGPoint(x: 0, y: (origin + CGFloat(selected - lower)) * 36), animated: false)
    reported = selected
    updateContentSize()
    updating = false
    setNeedsLayout()
  }
  private func updateContentSize() {
    // The extra viewport space makes the last row reachable at its center.
    scroll.contentSize = CGSize(
      width: max(1, bounds.width - 14), height: CGFloat(count * (looping ? 1000 : 1) - 1) * 36 + 124
    )
  }
  override func layoutSubviews() {
    super.layoutSubviews()
    top.frame = CGRect(x: 7, y: 18, width: bounds.width - 14, height: 44)
    centerBand.frame = CGRect(x: 7, y: 62, width: bounds.width - 14, height: 36)
    bottom.frame = CGRect(x: 7, y: 98, width: bounds.width - 14, height: 44)
    scroll.frame = CGRect(x: 7, y: 18, width: bounds.width - 14, height: 124)
    let spacing = (window?.bounds.width ?? UIScreen.main.bounds.width) * 0.02
    upperLine.frame = CGRect(x: spacing, y: 61.5, width: bounds.width - 2 * spacing, height: 2)
    lowerLine.frame = CGRect(x: spacing, y: 97.5, width: bounds.width - 2 * spacing, height: 2)
    upperLine.backgroundColor = ink
    lowerLine.backgroundColor = ink
    updateContentSize()
    render()
  }
  private func value(_ index: Int) -> Int { lower + ((index % count) + count) % count }
  private func render() {
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    let first = Int(floor(offset)) - 3
    for band in 0..<3 {
      let container = [top, centerBand, bottom][band]
      for index in 0..<7 {
        let item = first + index
        let label = rows[band][index]
        let delta = (CGFloat(item) - offset) * 36
        let angle = -delta / 124 * 2 * asin(1 / 1.5) / 0.95
        label.isHidden = (!looping && (item < 0 || item >= count)) || abs(angle) > .pi / 2
        label.text = year ? String(value(item)) : String(format: "%02d", value(item))
        label.font = font
        label.textColor = ink
        label.bounds = CGRect(x: 0, y: 0, width: container.bounds.width, height: 36)
        label.layer.position = CGPoint(x: container.bounds.width / 2, y: 80 - container.frame.minY)
        if band == 1 {
          label.layer.transform = CATransform3DMakeTranslation(0, delta, 0)
          label.alpha = 1
        } else {
          let radius: CGFloat = 93
          var transform = CATransform3DIdentity
          transform.m22 = cos(angle)
          transform.m23 = sin(angle)
          transform.m24 = -0.003 * sin(angle)
          transform.m42 = -radius * sin(angle)
          transform.m43 = radius * (cos(angle) - 1)
          transform.m44 = 1 + 0.003 * radius * (1 - cos(angle))
          label.layer.transform = transform
          label.alpha = 0.447
        }
      }
    }
    accessibilityValue = year ? String(reported) : String(format: "%02d", reported)
    CATransaction.commit()
  }
  func scrollViewDidScroll(_ scrollView: UIScrollView) {
    guard !updating else { return }
    let index = looping ? Int(offset.rounded()) : max(0, min(count - 1, Int(offset.rounded())))
    let selected = value(index)
    if selected != reported {
      reported = selected
      changed?(selected)
    }
    render()
  }
  func scrollViewWillEndDragging(
    _ scrollView: UIScrollView, withVelocity velocity: CGPoint,
    targetContentOffset: UnsafeMutablePointer<CGPoint>
  ) {
    targetContentOffset.pointee.y = (targetContentOffset.pointee.y / 36).rounded() * 36
  }
  @objc private func tap(_ gesture: UITapGestureRecognizer) {
    let displacement = (gesture.location(in: self).y - 80) / 36
    move(to: Int(offset.rounded()) + Int(displacement.rounded()))
  }
  private func move(to index: Int) {
    let selected = looping ? index : max(0, min(count - 1, index))
    UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseInOut, .beginFromCurrentState])
    {
      self.scroll.contentOffset.y = (self.origin + CGFloat(selected)) * 36
    }
  }
  override func accessibilityIncrement() { move(to: Int(offset.rounded()) + 1) }
  override func accessibilityDecrement() { move(to: Int(offset.rounded()) - 1) }
}
