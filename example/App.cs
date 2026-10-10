using System.Globalization;
using DotNative;
using DotNative.WheelDatePicker;

namespace WheelDatePickerDemo;

public sealed class App(IWheelDatePicker picker, IUiDispatcher dispatcher) : Component
{
    private readonly State<string> result = new("Choose a date");
    private readonly State<bool> busy = new(false);
    private readonly State<string> locale = new("en-US");

    public override Element Build() =>
        new VStack(
            new Text("DotNative.WheelDatePicker").FontSize(24),
            new RadioGroup<string>(
                locale.Value,
                value => locale.Value = value,
                new("en-US", "en-US — M / D / Y"),
                new("sr-Latn-RS", "sr-Latn-RS — D / M / Y"),
                new("ja-JP", "ja-JP — Y / M / D")
            ),
            new Text(result.Value),
            new Button(busy.Value ? "Opening…" : "Choose date", () => _ = ChooseAsync())
        )
            .Padding(24)
            .BackgroundColor(Color.White)
            .WidthPercent(100)
            .HeightPercent(100);

    private async Task ChooseAsync()
    {
        if (busy.Value)
            return;
        busy.Value = true;
        string text;
        var selectedLocale = locale.Value;
        try
        {
            text =
                (
                    await picker.ShowAsync(
                        new DatePickerOptions
                        {
                            Locale = selectedLocale,
                            MinimumDate = new DateOnly(2023, 1, 1),
                            MaximumDate = new DateOnly(2025, 12, 31),
                            InitialDate = new DateOnly(2024, 1, 31),
                        }
                    )
                )?.ToString("d", CultureInfo.GetCultureInfo(selectedLocale))
                ?? "Cancelled";
        }
        catch (Exception error)
        {
            text = error.Message;
        }
        dispatcher.Post(() =>
        {
            result.Value = text;
            busy.Value = false;
        });
    }
}
