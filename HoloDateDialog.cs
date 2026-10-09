using System.Globalization;
using DotNative;

namespace DotNative.HoloDatePicker;

internal sealed class HoloDateDialog : Component, IDisposable
{
    private readonly DatePickerOptions options;
    private readonly Action<DateOnly?> close;
    private readonly IUiDispatcher dispatcher;
    private readonly State<DateOnly> selected;
    private readonly AnimationController fade;
    private readonly WheelPicker day,
        month,
        year;
    private readonly FontSource? itemFont,
        titleFont,
        actionFont;
    private bool closing;
    private bool disposed;
    private float screenWidth = 375;

    private static Color ColorOf(uint bits) =>
        new((byte)(bits >> 16), (byte)(bits >> 8), (byte)bits, (byte)(bits >> 24));

    internal HoloDateDialog(
        DatePickerOptions options,
        Action<DateOnly?> close,
        IUiDispatcher dispatcher
    )
    {
        this.options = options;
        this.close = close;
        this.dispatcher = dispatcher;
        var initial = options.InitialDate ?? DateOnly.FromDateTime(DateTime.Today);
        if (initial < options.MinimumDate)
            initial = options.MinimumDate;
        if (initial > options.MaximumDate)
            initial = options.MaximumDate;
        selected = new(initial);
        itemFont = options.FontData is { } data
            ? FontSource.FromBytes(options.FontFamily, data)
            : null;
        titleFont = options.TitleFontData is { } title
            ? FontSource.FromBytes(options.TitleFontFamily, title)
            : null;
        actionFont = options.ActionFontData is { } action
            ? FontSource.FromBytes(options.ActionFontFamily, action)
            : null;
        fade = new(dispatcher);
        WheelPicker Wheel(string[] items, int index, Action<int> changed) =>
            new(items, index, changed, dispatcher)
            {
                Looping = options.Looping,
                Font = itemFont,
                FontSize = options.FontSize,
                Foreground = ColorOf(options.ItemColor),
            };
        var months = MonthRange(initial.Year);
        var days = DayRange(initial.Year, initial.Month);
        day = Wheel(
            Labels(days.First, days.Last),
            initial.Day - days.First,
            index => SelectDay(index)
        );
        month = Wheel(
            Labels(months.First, months.Last),
            initial.Month - months.First,
            index => SelectMonth(index)
        );
        year = Wheel(
            Labels(options.MinimumDate.Year, options.MaximumDate.Year, true),
            initial.Year - options.MinimumDate.Year,
            index => SelectYear(index)
        );
        dispatcher.Post(() =>
        {
            if (!disposed)
                _ = fade.AnimateToAsync(1, TimeSpan.FromMilliseconds(150), Easings.EaseOut);
        });
    }

    private static string[] Labels(int first, int last, bool year = false) =>
        Enumerable
            .Range(first, last - first + 1)
            .Select(x => x.ToString(year ? "0000" : "00", CultureInfo.InvariantCulture))
            .ToArray();

    private (int First, int Last) MonthRange(int value) =>
        (
            value == options.MinimumDate.Year ? options.MinimumDate.Month : 1,
            value == options.MaximumDate.Year ? options.MaximumDate.Month : 12
        );

    private (int First, int Last) DayRange(int y, int m) =>
        (
            y == options.MinimumDate.Year && m == options.MinimumDate.Month
                ? options.MinimumDate.Day
                : 1,
            y == options.MaximumDate.Year && m == options.MaximumDate.Month
                ? options.MaximumDate.Day
                : DateTime.DaysInMonth(y, m)
        );

    private void SelectDay(int index)
    {
        var value = selected.Value;
        selected.Value = new(
            value.Year,
            value.Month,
            DayRange(value.Year, value.Month).First + index
        );
    }

    private void SelectMonth(int index)
    {
        var value = selected.Value;
        var m = MonthRange(value.Year).First + index;
        var bounds = DayRange(value.Year, m);
        var d = Math.Clamp(value.Day, bounds.First, bounds.Last);
        selected.Value = new(value.Year, m, d);
        day.SetItems(Labels(bounds.First, bounds.Last), d - bounds.First);
    }

    private void SelectYear(int index)
    {
        var value = selected.Value;
        var y = options.MinimumDate.Year + index;
        var months = MonthRange(y);
        var m = Math.Clamp(value.Month, months.First, months.Last);
        var bounds = DayRange(y, m);
        var d = Math.Clamp(value.Day, bounds.First, bounds.Last);
        selected.Value = new(y, m, d);
        month.SetItems(Labels(months.First, months.Last), m - months.First);
        day.SetItems(Labels(bounds.First, bounds.Last), d - bounds.First);
    }

    private async Task FinishAsync(DateOnly? value)
    {
        if (closing || disposed)
            return;
        closing = true;
        try
        {
            await fade.AnimateToAsync(0, TimeSpan.FromMilliseconds(150), Easings.EaseOut);
            if (!disposed)
                close(value);
        }
        catch (OperationCanceledException) { }
    }

    public override Element Build(BuildContext context)
    {
        var viewport = context.Get<ViewportSize>();
        screenWidth = viewport.Width;
        var width = Math.Max(1, Math.Min(328, viewport.Width - 80));
        var titleHeight = MathF.Ceiling(options.TitleFontSize * 4 / 3);
        var height = 24 + titleHeight + 160 + 72;
        var ink = ColorOf(options.TextColor);
        Text Title() =>
            titleFont is { } font
                ? new Text(options.Title).FontSource(font).FontWeight(FontWeight.Bold)
                : new Text(options.Title).FontWeight(FontWeight.Bold);
        Button Action(string text, Action action) =>
            (
                actionFont is { } font
                    ? new Button(text, action).FontSource(font)
                    : new Button(text, action)
            )
                .FontSize(options.ActionFontSize)
                .FontWeight(FontWeight.SemiBold)
                .ForegroundColor(ink)
                .MinWidth(64)
                .Height(48)
                .Padding(12, 0)
                .CornerRadius(20)
                .OnState(InteractionState.Pressed, new(Background: new Color(0, 0, 0, 26)));
        // A transparent surface blocks taps inside the panel from reaching the dismissing backdrop.
        var panel = new VStack(
            new Button("", () => { })
                .Position(PositionMode.Absolute)
                .Left(0)
                .Top(0)
                .WidthPercent(100)
                .HeightPercent(100),
            Title()
                .FontSize(options.TitleFontSize)
                .ForegroundColor(ink)
                .Position(PositionMode.Absolute)
                .Left(24)
                .Right(24)
                .Top(24)
                .Height(titleHeight),
            new HStack(
                new Container(day).Grow(),
                new Container(month).Grow(),
                new Container(year).Grow()
            )
                .Position(PositionMode.Absolute)
                .Left(14)
                .Right(14)
                .Top(24 + titleHeight)
                .Height(160),
            new HStack(
                Action(options.ConfirmText, () => _ = FinishAsync(selected.Value)),
                Action(options.CancelText, () => _ = FinishAsync(null))
            )
                .Spacing(8)
                .MainAlignment(MainAxisAlignment.End)
                .Position(PositionMode.Absolute)
                .Left(24)
                .Right(24)
                .Top(height - 72)
                .Height(48)
        )
            .Width(width)
            .Height(height)
            .CornerRadius(28)
            .BackgroundColor(ColorOf(options.BackgroundColor))
            .ClipToBounds()
            .Position(PositionMode.Absolute)
            .Left((viewport.Width - width) / 2)
            .Top(
                options.SafeArea.Top
                    + (viewport.Height - options.SafeArea.Top - options.SafeArea.Bottom - height)
                        / 2
            );
        return new VStack(
            new Button("", () => _ = FinishAsync(null))
                .WidthPercent(100)
                .HeightPercent(100)
                .Position(PositionMode.Absolute)
                .Left(0)
                .Top(0)
                .BackgroundColor(new Color(0, 0, 0, 138)),
            panel
        )
            .WidthPercent(100)
            .HeightPercent(100)
            .Opacity(fade.Value);
    }

    public void Dispose()
    {
        disposed = true;
        fade.Dispose();
        day.Dispose();
        month.Dispose();
        year.Dispose();
    }
}
