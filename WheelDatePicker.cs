using DotNative;
using DotNative.Plugins;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace DotNative.WheelDatePicker;

public interface IWheelDatePicker
{
    /// <summary>Returns the selected date, or null when the dialog is dismissed.</summary>
    Task<DateOnly?> ShowAsync(
        DatePickerOptions? options = null,
        CancellationToken cancellationToken = default
    );
}

public enum DatePickerRenderer
{
    Automatic,
    SharedWheel,
}

public sealed record DatePickerOptions
{
    public DatePickerRenderer Renderer { get; init; } = DatePickerRenderer.Automatic;

    /// <summary>Locale tag such as en-US or sr-Latn-RS. Null uses the current platform locale.</summary>
    public string? Locale { get; init; }
    public DateOnly MinimumDate { get; init; } = new(1900, 1, 1);
    public DateOnly MaximumDate { get; init; } = new(2100, 12, 31);
    public DateOnly? InitialDate { get; init; }
    public string Title { get; init; } = "Choose date";
    public string ConfirmText { get; init; } = "OK";
    public string CancelText { get; init; } = "Cancel";
    public bool Looping { get; init; } = true;
    public uint BackgroundColor { get; init; } = 0xfff6f6f6;
    public uint TextColor { get; init; } = 0xff000000;
    public string FontFamily { get; init; } = "sans-serif";
    public string TitleFontFamily { get; init; } = "sans-serif";
    public string ActionFontFamily { get; init; } = "sans-serif";
    public EdgeInsets SafeArea { get; init; }
    public float FontSize { get; init; } = 16;
    public uint ItemColor { get; init; } = 0xff000000;
    public float TitleFontSize { get; init; } = 24;
    public float ActionFontSize { get; init; } = 14;
    public byte[]? FontData { get; init; }
    public byte[]? TitleFontData { get; init; }
    public byte[]? ActionFontData { get; init; }
}

internal sealed class WheelDatePickerService(
    IOverlayPresenter overlays,
    IUiDispatcher dispatcher,
    IPlatformChannels channels,
    PresentationTarget target,
    DatePickerOptions defaults
) : IWheelDatePicker
{
    private readonly NativeDateRenderer native = new(channels, defaults);

    public Task<DateOnly?> ShowAsync(
        DatePickerOptions? options = null,
        CancellationToken cancellationToken = default
    )
    {
        var value = options ?? defaults;
        if (value.Locale is { } locale)
            value = value with { Locale = DatePickerLocale.Resolve(locale).Name };
        if (!Enum.IsDefined(value.Renderer))
            throw new ArgumentOutOfRangeException(nameof(options));
        if (
            value.Renderer == DatePickerRenderer.Automatic
            && target.Platform is NativePlatform.IOS or NativePlatform.Android
        )
            return native.ShowAsync(value, cancellationToken);
        if (
            value.MinimumDate > value.MaximumDate
            || value.MaximumDate.Year - value.MinimumDate.Year > 500
        )
            throw new ArgumentException("Invalid date range (maximum 500 years).", nameof(options));
        foreach (var size in new[] { value.FontSize, value.TitleFontSize, value.ActionFontSize })
            if (!float.IsFinite(size) || size <= 0)
                throw new ArgumentException(
                    "Font sizes must be positive and finite.",
                    nameof(options)
                );
        return overlays.ShowAsync<DateOnly?>(
            close => new WheelDateDialog(value, close, dispatcher),
            cancellationToken
        );
    }
}

public static class WheelDatePickerServices
{
    public static IServiceCollection AddWheelDatePicker(
        this IServiceCollection services,
        DatePickerOptions? options = null
    )
    {
        services.TryAddSingleton<IWheelDatePicker>(p => new WheelDatePickerService(
            p.GetRequiredService<IOverlayPresenter>(),
            p.GetRequiredService<IUiDispatcher>(),
            p.GetRequiredService<IPlatformChannels>(),
            p.GetRequiredService<PresentationTarget>(),
            options ?? new()
        ));
        return services;
    }
}

public static class WheelDatePickerServiceProviderExtensions
{
#if NET10_0_OR_GREATER
    extension(IServiceProvider services)
    {
        public IWheelDatePicker WheelDatePicker => services.GetRequiredService<IWheelDatePicker>();
    }
#else
    public static IWheelDatePicker WheelDatePicker(this IServiceProvider services) =>
        services.GetRequiredService<IWheelDatePicker>();
#endif
}
