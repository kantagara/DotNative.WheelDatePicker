using System.Globalization;
using System.Security.Cryptography;
using DotNative.Plugins;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace DotNative.HoloDatePicker;

public interface IHoloDatePicker
{
    /// <summary>Returns the selected date, or null when the dialog is dismissed.</summary>
    Task<DateOnly?> ShowAsync(
        DatePickerOptions? options = null,
        CancellationToken cancellationToken = default
    );
}

public sealed record DatePickerOptions
{
    public DateOnly MinimumDate { get; init; } = new(1900, 1, 1);
    public DateOnly MaximumDate { get; init; } = new(2100, 12, 31);
    public DateOnly? InitialDate { get; init; }
    public string Title { get; init; } = "Choose date";
    public string ConfirmText { get; init; } = "OK";
    public string CancelText { get; init; } = "Cancel";
    public bool Looping { get; init; } = true;
    public uint BackgroundColor { get; init; } = 0xfff6f6f6;
    public uint TextColor { get; init; } = 0xff000000;
    public string? FontFamily { get; init; }
    public float FontSize { get; init; } = 16;
    public uint ItemColor { get; init; } = 0xff000000;
    public float TitleFontSize { get; init; } = 24;
    public float ActionFontSize { get; init; } = 14;
    public byte[]? FontData { get; init; }
    public byte[]? TitleFontData { get; init; }
    public byte[]? ActionFontData { get; init; }
}

internal sealed class ChannelHoloDatePicker(IPlatformChannels channels, DatePickerOptions defaults)
    : IHoloDatePicker
{
    private readonly MethodChannel channel = channels.Get("dotnative.holodatepicker");
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly HashSet<string> loadedFonts = [];

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
        if (
            !float.IsFinite(value.FontSize)
            || value.FontSize <= 0
            || !float.IsFinite(value.TitleFontSize)
            || value.TitleFontSize <= 0
            || !float.IsFinite(value.ActionFontSize)
            || value.ActionFontSize <= 0
        )
            throw new ArgumentException("Font sizes must be positive and finite.", nameof(options));
        foreach (var data in new[] { value.FontData, value.TitleFontData, value.ActionFontData })
            if (data is { Length: < 12 or > 8388608 })
                throw new ArgumentException(
                    "Each font must contain 12 bytes to 8 MiB of TTF/OTF data.",
                    nameof(options)
                );
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var fontId = await LoadFontAsync(value.FontData, cancellationToken)
                .ConfigureAwait(false);
            var titleFontId = await LoadFontAsync(value.TitleFontData, cancellationToken)
                .ConfigureAwait(false);
            var actionFontId = await LoadFontAsync(value.ActionFontData, cancellationToken)
                .ConfigureAwait(false);
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
                        ["looping"] = value.Looping,
                        ["backgroundColor"] = (long)value.BackgroundColor,
                        ["textColor"] = (long)value.TextColor,
                        ["fontFamily"] = value.FontFamily,
                        ["fontSize"] = (double)value.FontSize,
                        ["titleFontSize"] = (double)value.TitleFontSize,
                        ["actionFontSize"] = (double)value.ActionFontSize,
                        ["fontDataId"] = fontId,
                        ["titleFontDataId"] = titleFontId,
                        ["actionFontDataId"] = actionFontId,
                        ["itemColor"] = (long)value.ItemColor,
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

    private async Task<string?> LoadFontAsync(byte[]? data, CancellationToken token)
    {
        if (data is null)
            return null;
        var id = Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
        if (loadedFonts.Contains(id))
            return id;
        const int chunkSize = 512 * 1024;
        var chunks = (data.Length + chunkSize - 1) / chunkSize;
        for (var index = 0; index < chunks; index++)
        {
            await channel
                .InvokeAsync(
                    "fontChunk",
                    new Dictionary<string, object?>
                    {
                        ["id"] = id,
                        ["index"] = index,
                        ["total"] = chunks,
                        ["data"] = data.AsSpan(
                                index * chunkSize,
                                Math.Min(chunkSize, data.Length - index * chunkSize)
                            )
                            .ToArray(),
                    },
                    token
                )
                .ConfigureAwait(false);
        }
        loadedFonts.Add(id);
        return id;
    }
}

public static class HoloDatePickerServices
{
    public static IServiceCollection AddHoloDatePicker(
        this IServiceCollection services,
        DatePickerOptions? options = null
    )
    {
        services.TryAddSingleton<IHoloDatePicker>(p => new ChannelHoloDatePicker(
            p.GetRequiredService<IPlatformChannels>(),
            options ?? new()
        ));
        return services;
    }
}

public static class HoloDatePickerServiceProviderExtensions
{
#if NET10_0_OR_GREATER
    extension(IServiceProvider services)
    {
        public IHoloDatePicker HoloDatePicker => services.GetRequiredService<IHoloDatePicker>();
    }
#else
    public static IHoloDatePicker HoloDatePicker(this IServiceProvider services) =>
        services.GetRequiredService<IHoloDatePicker>();
#endif
}
