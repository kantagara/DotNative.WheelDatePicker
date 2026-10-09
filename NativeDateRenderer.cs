using System.Globalization;
using DotNative.Plugins;

namespace DotNative.HoloDatePicker;

internal sealed class NativeDateRenderer(IPlatformChannels channels, DatePickerOptions defaults)
    : IHoloDatePicker
{
    private readonly MethodChannel channel = channels.Get("dotnative.holodatepicker");
    private readonly SemaphoreSlim gate = new(1, 1);

    public async Task<DateOnly?> ShowAsync(
        DatePickerOptions? options = null,
        CancellationToken cancellationToken = default
    )
    {
        var value = options ?? defaults;
        if (value.MinimumDate > value.MaximumDate)
            throw new ArgumentException(
                "MinimumDate must not exceed MaximumDate.",
                nameof(options)
            );
        if (value.MaximumDate.Year - value.MinimumDate.Year > 500)
            throw new ArgumentException(
                "The supported range is at most 500 years.",
                nameof(options)
            );
        var date = value.InitialDate ?? DateOnly.FromDateTime(DateTime.Today);
        if (date < value.MinimumDate)
            date = value.MinimumDate;
        if (date > value.MaximumDate)
            date = value.MaximumDate;
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var result = await channel
                .InvokeAsync(
                    "show",
                    new Dictionary<string, object?>
                    {
                        ["minimumDate"] = value.MinimumDate.ToString(
                            "yyyy-MM-dd",
                            CultureInfo.InvariantCulture
                        ),
                        ["maximumDate"] = value.MaximumDate.ToString(
                            "yyyy-MM-dd",
                            CultureInfo.InvariantCulture
                        ),
                        ["initialDate"] = date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                        ["title"] = value.Title,
                        ["confirmText"] = value.ConfirmText,
                        ["cancelText"] = value.CancelText,
                    },
                    cancellationToken
                )
                .ConfigureAwait(false);
            if (result is null)
                return null;
            if (
                result is not string text
                || !DateOnly.TryParseExact(
                    text,
                    "yyyy-MM-dd",
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.None,
                    out var selected
                )
                || selected < value.MinimumDate
                || selected > value.MaximumDate
            )
                throw new InvalidDataException("The native picker returned an invalid date.");
            return selected;
        }
        finally
        {
            try
            {
                if (cancellationToken.IsCancellationRequested)
                    await channel
                        .InvokeAsync("cancel", cancellationToken: CancellationToken.None)
                        .ConfigureAwait(false);
            }
            finally
            {
                gate.Release();
            }
        }
    }
}
