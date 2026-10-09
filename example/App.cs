using DotNative;
using DotNative.HoloDatePicker;

namespace HoloDatePickerDemo;

public sealed class App(IHoloDatePicker picker, IUiDispatcher dispatcher) : Component
{
    private readonly State<string> result = new("Choose a date");
    private readonly State<bool> busy = new(false);

    public override Element Build() =>
        new VStack(
            new Text("DotNative.HoloDatePicker").FontSize(24),
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
        try
        {
            text =
                (
                    await picker.ShowAsync(
                        new DatePickerOptions
                        {
                            MinimumDate = new DateOnly(2023, 1, 1),
                            MaximumDate = new DateOnly(2025, 12, 31),
                            InitialDate = new DateOnly(2024, 1, 31),
                        }
                    )
                )?.ToString("dd.MM.yyyy") ?? "Cancelled";
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
