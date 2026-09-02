"use client";

import { useState, useEffect, useRef } from "react";
import { useWebHaptics } from "web-haptics/react";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { signIn, signOut } from "next-auth/react";

import PixelIcon from "./PixelIcon";
import { Button } from "./ui/button";
import Squircle from "./Squircle";
import { Menu, X, Home, BookOpen, Map, BarChart3, Trophy, Palette, Boxes, Gift, Wrench, Rainbow, Circle, ArrowLeftRight, Sparkles, Instagram, Sun, Moon, LogIn, LogOut, ChevronDown, ChevronUp, ClipboardList, Search, User, Settings, ImageIcon } from "lucide-react";
import { FaDiscord, FaTiktok, FaYoutube } from "react-icons/fa";
import config from "../data/site-config.json";

const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
    Home, BookOpen, Map, BarChart3, Trophy, Palette, Boxes, Gift, Wrench, Rainbow, Circle, ArrowLeftRight, Sparkles, ImageIcon, Search,
    youtube: FaYoutube,
    instagram: Instagram,
    tiktok: FaTiktok,
    discord: FaDiscord
};

const featuredToolUrls = [
    "/tools/rgb-nickname",
    "/tools/block-gradient",
    "/tools/enchantment-planner",
    "/tools/portal-calculator"
];

const toolDescriptions: Record<string, string> = {
    "/tools/rgb-nickname": "Create coloured nicknames",
    "/tools/block-gradient": "Blend block palettes",
    "/tools/enchantment-planner": "Build legal gear loadouts",
    "/tools/portal-calculator": "Link Overworld and Nether"
};

const featuredTools = featuredToolUrls
    .map((url) => config.navbar.tools.find((tool) => tool.url === url))
    .filter((tool): tool is (typeof config.navbar.tools)[number] => Boolean(tool));
const compactTools = config.navbar.tools.filter((tool) => !featuredToolUrls.includes(tool.url));

interface UserData {
    name: string;
    avatarUrl: string;
    minecraftUuid: string | null;
    minecraftUsername: string | null;
    minecraftNickname: string | null;
    role: string;
}

export default function Navbar({ user }: { user?: UserData | null }) {
    const playerName = user?.minecraftNickname || user?.minecraftUsername || user?.name || "User";
    const { trigger } = useWebHaptics();
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [isToolsOpen, setIsToolsOpen] = useState(false);
    const [isUserMenuOpen, setIsUserMenuOpen] = useState(false);
    const userBtnRef = useRef<HTMLButtonElement>(null);
    const [dropdownPos, setDropdownPos] = useState({ top: 0, right: 0 });
    const currentPath = usePathname();
    const [isDark, setIsDark] = useState(false);
    const [ipCopied, setIpCopied] = useState(false);
    const ipCopiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
        setIsDark(document.documentElement.classList.contains("dark"));

        const handleClick = (e: MouseEvent) => {
            const target = e.target as HTMLElement;
            if (!target.closest('.user-menu-wrapper')) {
                setIsUserMenuOpen(false);
            }
            if (!target.closest('.tools-dropdown-wrapper')) {
                setIsToolsOpen(false);
            }
        };
        document.addEventListener('click', handleClick);
        return () => document.removeEventListener('click', handleClick);
    }, []);

    useEffect(() => {
        if (!isMenuOpen) return;
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        return () => {
            document.body.style.overflow = previousOverflow;
        };
    }, [isMenuOpen]);

    const toggleDarkMode = () => {
        trigger();
        const newDark = !isDark;
        setIsDark(newDark);
        document.documentElement.classList.toggle("dark", newDark);
        localStorage.setItem("theme", newDark ? "dark" : "light");
    };

    const toggleMenu = () => { trigger(); setIsMenuOpen(!isMenuOpen); };

    const copyIp = () => {
        trigger();
        navigator.clipboard.writeText("crabcraft.net");
        setIpCopied(true);
        if (ipCopiedTimer.current) clearTimeout(ipCopiedTimer.current);
        ipCopiedTimer.current = setTimeout(() => setIpCopied(false), 2000);
    };

    return (
        <div data-site-navbar className="sticky top-4 z-50 px-4 lg:px-8 relative">
        <nav className="container mx-auto relative">
            <Squircle
                cornerRadius={20}
                className="absolute inset-0 z-0 bg-paper-2/80 backdrop-blur-2xl backdrop-saturate-150 shadow-lg shadow-black/5 dark:shadow-black/30"
            />
            <div className="relative z-10 px-6 lg:px-8">
                <div className="flex items-center h-14 md:h-20">
                    <div className="flex-1 flex justify-start">
                        <div className="flex items-center gap-3">
                            <Link href="/" className="flex items-center">
                                <Image
                                    src="/logo.png"
                                    alt="CrabCraft logo"
                                    width={44}
                                    height={44}
                                    loading="eager"
                                    unoptimized
                                    className="object-contain w-9 h-9 md:w-[44px] md:h-[44px]"
                                />
                            </Link>
                            <button
                                type="button"
                                onClick={copyIp}
                                data-umami-event="server-ip-copied"
                                data-umami-event-location="navbar"
                                title="Click to copy server IP"
                                aria-label="Copy server IP"
                                className="relative font-bold text-base text-orange-500 tracking-wide text-left cursor-pointer"
                            >
                                <span className={`transition-opacity duration-300 ${ipCopied ? "opacity-0" : "opacity-100"}`}>CrabCraft.net</span>
                                <span aria-hidden={!ipCopied} className={`absolute inset-0 transition-opacity duration-300 ${ipCopied ? "opacity-100" : "opacity-0"}`}>IP copied</span>
                            </button>
                        </div>
                    </div>

                    <div className="hidden md:flex flex-1 justify-center items-center space-x-4 lg:space-x-6">
                        {config.navbar.links.map((link) => {
                            const Icon = iconMap[link.icon];
                            const isExternal = link.url.startsWith("http");
                            const isActive = !isExternal && (currentPath === link.url || (link.url !== '/' && currentPath.startsWith(link.url)));
                            const cls = `flex items-center gap-1.5 hover:text-orange-500 transition-colors duration-200 font-bold text-xs lg:text-sm uppercase whitespace-nowrap ${isActive ? "text-orange-500 underline decoration-orange-500 decoration-2 underline-offset-4" : "text-gray-800 dark:text-gray-200"}`;
                            return isExternal ? (
                                <a key={link.name} href={link.url} target="_blank" rel="noopener noreferrer" className={cls}>
                                    {Icon && <Icon className="w-4 h-4" />}
                                    {link.name}
                                </a>
                            ) : (
                                <Link key={link.name} href={link.url} className={cls}>
                                    {Icon && <Icon className="w-4 h-4" />}
                                    {link.name}
                                </Link>
                            );
                        })}

                        {/* Tools dropdown */}
                        <div
                            className="tools-dropdown-wrapper relative"
                            onMouseEnter={() => setIsToolsOpen(true)}
                            onMouseLeave={() => setIsToolsOpen(false)}
                        >
                            <button
                                onClick={() => setIsToolsOpen(!isToolsOpen)}
                                className={`flex items-center gap-1.5 hover:text-orange-500 transition-colors duration-200 font-bold text-xs lg:text-sm uppercase whitespace-nowrap cursor-pointer ${currentPath.startsWith("/tools") ? "text-orange-500 underline decoration-orange-500 decoration-2 underline-offset-4" : "text-gray-800 dark:text-gray-200"}`}
                            >
                                <Wrench className="w-4 h-4" />
                                Tools
                                {isToolsOpen ? <ChevronUp className="w-3 h-3 opacity-50" /> : <ChevronDown className="w-3 h-3 opacity-50" />}
                            </button>
                            {isToolsOpen && (
                                <div className="absolute left-1/2 -translate-x-1/2 top-full pt-3 z-50">
                                    <div className="w-[600px] max-w-[calc(100vw-2rem)] rounded-2xl bg-paper-2/95 backdrop-blur-2xl shadow-xl shadow-black/10 dark:shadow-black/30 p-3 animate-[scaleIn_0.15s_ease-out]">
                                        <div className="grid grid-cols-2 gap-2">
                                            {featuredTools.map((tool) => {
                                                const ToolIcon = iconMap[tool.icon];
                                                const isToolActive = currentPath === tool.url;
                                                return (
                                                    <Link
                                                        key={tool.name}
                                                        href={tool.url}
                                                        onClick={() => setIsToolsOpen(false)}
                                                        className={`group rounded-xl p-3 transition-colors ${isToolActive ? "bg-orange-500 text-white" : "bg-paper hover:bg-orange-500 hover:text-white text-gray-800 dark:text-gray-200"}`}
                                                    >
                                                        <div className="flex items-start gap-3">
                                                            <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg transition-colors ${isToolActive ? "bg-white/20" : "bg-orange-500/10 text-orange-500 group-hover:bg-white/20 group-hover:text-white"}`}>
                                                                {ToolIcon && <ToolIcon className="w-4 h-4" />}
                                                            </div>
                                                            <div className="min-w-0">
                                                                <div className="text-sm font-bold leading-tight">
                                                                    {tool.name}
                                                                </div>
                                                                <div className={`mt-1 text-xs leading-snug ${isToolActive ? "text-white/80" : "text-gray-500 dark:text-gray-400 group-hover:text-white/80"}`}>
                                                                    {toolDescriptions[tool.url]}
                                                                </div>
                                                            </div>
                                                        </div>
                                                    </Link>
                                                );
                                            })}
                                        </div>

                                        <div className="mt-3 border-t border-line/70 pt-3">
                                            <div className="mb-2 px-1 text-[10px] font-bold uppercase tracking-wide text-gray-400">
                                                More tools
                                            </div>
                                            <div className="grid grid-cols-2 gap-1.5">
                                                {compactTools.map((tool) => {
                                                    const ToolIcon = iconMap[tool.icon];
                                                    const isToolActive = currentPath === tool.url;
                                                    return (
                                                        <Link
                                                            key={tool.name}
                                                            href={tool.url}
                                                            onClick={() => setIsToolsOpen(false)}
                                                            className={`flex items-center gap-2 rounded-lg px-3 py-2 text-xs font-bold transition-colors ${isToolActive ? "bg-orange-500/10 text-orange-500" : "text-gray-600 dark:text-gray-300 hover:bg-paper"}`}
                                                        >
                                                            {ToolIcon && <ToolIcon className="w-4 h-4 shrink-0" />}
                                                            <span className="truncate">{tool.name}</span>
                                                        </Link>
                                                    );
                                                })}
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>

                    <div className="hidden md:flex flex-1 justify-end items-center gap-5 text-gray-800 dark:text-gray-200">
                        <button
                            onClick={() => window.dispatchEvent(new Event("open-command-menu"))}
                            data-umami-event="site-search-opened"
                            data-umami-event-location="navbar-desktop"
                            aria-label="Search"
                            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-paper transition-colors cursor-pointer"
                        >
                            <Search className="w-4 h-4 text-gray-400" />
                        </button>
                        {user ? (
                            <div className="user-menu-wrapper">
                                <button
                                    ref={userBtnRef}
                                    onClick={() => {
                                        if (userBtnRef.current) {
                                            const rect = userBtnRef.current.getBoundingClientRect();
                                            setDropdownPos({ top: rect.bottom + 8, right: window.innerWidth - rect.right - 15 });
                                        }
                                        setIsUserMenuOpen(!isUserMenuOpen);
                                    }}
                                    className="flex items-center gap-2 cursor-pointer hover:opacity-80 transition-opacity"
                                >
                                    {user.minecraftUuid ? (
                                        <PixelIcon
                                            src={`https://mc-heads.net/avatar/${user.minecraftUuid}/56.png`}
                                            alt={playerName}
                                            size={28}
                                            imgClassName="rounded"
                                        />
                                    ) : (
                                        <Image
                                            src={user.avatarUrl}
                                            alt={user.name}
                                            width={28}
                                            height={28}
                                            className="rounded-full"
                                        />
                                    )}
                                    <span className="text-xs font-bold">{playerName}</span>
                                    {isUserMenuOpen ? <ChevronUp className="w-3 h-3 text-gray-400" /> : <ChevronDown className="w-3 h-3 text-gray-400" />}
                                </button>
                            </div>
                        ) : (
                            <div className="flex items-center gap-2">
                                <button onClick={toggleDarkMode} aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"} className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-paper transition-colors cursor-pointer">
                                    {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                                </button>
                                <button
                                    onClick={() => signIn("discord")}
                                    data-umami-event="discord-sign-in-started"
                                    data-umami-event-location="navbar-desktop"
                                    className="flex items-center gap-1.5 text-xs font-bold text-gray-600 dark:text-gray-300 hover:text-orange-500 transition-colors cursor-pointer"
                                >
                                    <LogIn className="w-4 h-4" />
                                    Sign In
                                </button>
                            </div>
                        )}
                    </div>

                    <div className="md:hidden flex-1 flex justify-end items-center gap-1">
                        <button onClick={toggleDarkMode} aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"} className="w-9 h-9 flex items-center justify-center rounded-lg text-gray-500 dark:text-gray-400 hover:bg-white/10 transition-colors cursor-pointer">
                            {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                        </button>
                        <Button variant="ghost" size="icon" onClick={toggleMenu} aria-label={isMenuOpen ? "Close menu" : "Open menu"} className="text-gray-400 dark:text-gray-300 hover:bg-white/10">
                            {isMenuOpen ? (
                                <X className="h-6 w-6" />
                            ) : (
                                <Menu className="h-6 w-6" />
                            )}
                        </Button>
                    </div>
                </div>

            </div>
        </nav>
        {isUserMenuOpen && user && (
            <div className="hidden md:block fixed w-44 bg-paper-2 rounded-xl shadow-lg overflow-hidden z-50 animate-[scaleIn_0.15s_ease-out] user-menu-wrapper" style={{ top: dropdownPos.top, right: dropdownPos.right }}>
                {user.minecraftUuid && (
                    <Link
                        href={`/stats/${user.minecraftUuid}`}
                        className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-gray-700 dark:text-gray-300 hover:bg-paper transition-colors"
                    >
                        <User className="w-4 h-4" />
                        Your Profile
                    </Link>
                )}
                <Link
                    href="/wrapped"
                    className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-gray-700 dark:text-gray-300 hover:bg-paper transition-colors"
                >
                    <Gift className="w-4 h-4" />
                    Wrapped
                </Link>
                <Link
                    href="/settings"
                    className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-gray-700 dark:text-gray-300 hover:bg-paper transition-colors"
                >
                    <Settings className="w-4 h-4" />
                    Settings
                </Link>
                {(user.role === "moderator" || user.role === "admin") && (
                    <Link
                        href="/admin"
                        className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-orange-500 hover:bg-paper transition-colors"
                    >
                        <Wrench className="w-4 h-4" />
                        Admin Panel
                    </Link>
                )}
                <button
                    onClick={() => signOut()}
                    className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-red-500 hover:bg-paper transition-colors cursor-pointer"
                >
                    <LogOut className="w-4 h-4" />
                    Sign Out
                </button>
            </div>
        )}
        {isMenuOpen && (
                <div
                    className="md:hidden mt-2 bg-paper-2/90 backdrop-blur-2xl backdrop-saturate-150 rounded-2xl shadow-lg overflow-y-auto overscroll-contain max-h-[calc(100dvh-6rem)] absolute left-4 right-4 animate-[scaleIn_0.15s_ease-out]"
                >
                    <div className="p-4 space-y-1">
                        {/* Search */}
                        <button
                            onClick={() => { setIsMenuOpen(false); window.dispatchEvent(new Event("open-command-menu")); }}
                            data-umami-event="site-search-opened"
                            data-umami-event-location="navbar-mobile"
                            className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm text-gray-600 dark:text-gray-400 hover:bg-paper/60 dark:hover:bg-white/5 transition-colors w-full cursor-pointer mb-2"
                        >
                            <Search className="w-4 h-4" />
                            Search players
                        </button>

                        {/* Nav links */}
                        {config.navbar.links.map((link) => {
                            const Icon = iconMap[link.icon];
                            const isExternal = link.url.startsWith("http");
                            const isActive = !isExternal && (currentPath === link.url || (link.url !== '/' && currentPath.startsWith(link.url)));
                            const cls = `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${isActive ? "bg-orange-500/10 text-orange-500" : "text-gray-700 dark:text-gray-300 hover:bg-paper/60 dark:hover:bg-white/5"}`;
                            return isExternal ? (
                                <a key={link.name} href={link.url} target="_blank" rel="noopener noreferrer" className={cls} onClick={() => setIsMenuOpen(false)}>
                                    {Icon && <Icon className="w-4 h-4" />}
                                    {link.name}
                                </a>
                            ) : (
                                <Link key={link.name} href={link.url} className={cls} onClick={() => setIsMenuOpen(false)}>
                                    {Icon && <Icon className="w-4 h-4" />}
                                    {link.name}
                                </Link>
                            );
                        })}

                        {/* Tools section */}
                        <div className={`flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium ${currentPath.startsWith("/tools") ? "text-orange-500" : "text-gray-700 dark:text-gray-300"}`}>
                            <Wrench className="w-4 h-4" />
                            Tools
                        </div>
                        {config.navbar.tools.map((tool) => {
                            const ToolIcon = iconMap[tool.icon];
                            const isActive = currentPath === tool.url;
                            return (
                                <Link
                                    key={tool.name}
                                    href={tool.url}
                                    className={`flex items-center gap-3 pl-10 pr-3 py-2 rounded-xl text-sm font-medium transition-colors ${isActive ? "bg-orange-500/10 text-orange-500" : "text-gray-500 dark:text-gray-400 hover:bg-paper/60 dark:hover:bg-white/5"}`}
                                    onClick={() => setIsMenuOpen(false)}
                                >
                                    {ToolIcon && <ToolIcon className="w-4 h-4" />}
                                    {tool.name}
                                </Link>
                            );
                        })}

                        {/* User section */}
                        {user ? (
                            <div className="pt-2 mt-2 space-y-1">
                                <div className="flex items-center gap-3 px-3 py-2">
                                    {user.minecraftUuid ? (
                                        <PixelIcon
                                            src={`https://mc-heads.net/avatar/${user.minecraftUuid}/64.png`}
                                            alt={playerName}
                                            size={28}
                                            imgClassName="rounded"
                                        />
                                    ) : (
                                        <Image
                                            src={user.avatarUrl}
                                            alt={user.name}
                                            width={28}
                                            height={28}
                                            className="rounded-full"
                                        />
                                    )}
                                    <span className="text-sm font-bold text-gray-800 dark:text-gray-200 flex-1">{playerName}</span>
                                </div>
                                {user.minecraftUuid && (
                                    <Link href={`/stats/${user.minecraftUuid}`} className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm text-gray-600 dark:text-gray-400 hover:bg-paper/60 dark:hover:bg-white/5 transition-colors" onClick={() => setIsMenuOpen(false)}>
                                        <User className="w-4 h-4" />
                                        Your Profile
                                    </Link>
                                )}
                                <Link href="/wrapped" className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm text-gray-600 dark:text-gray-400 hover:bg-paper/60 dark:hover:bg-white/5 transition-colors" onClick={() => setIsMenuOpen(false)}>
                                    <Gift className="w-4 h-4" />
                                    Wrapped
                                </Link>
                                <Link href="/settings" className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm text-gray-600 dark:text-gray-400 hover:bg-paper/60 dark:hover:bg-white/5 transition-colors" onClick={() => setIsMenuOpen(false)}>
                                    <Settings className="w-4 h-4" />
                                    Settings
                                </Link>
                                {(user.role === "moderator" || user.role === "admin") && (
                                    <Link href="/admin" className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm text-orange-500 hover:bg-orange-500/10 transition-colors" onClick={() => setIsMenuOpen(false)}>
                                        <Wrench className="w-4 h-4" />
                                        Admin Panel
                                    </Link>
                                )}
                                <button onClick={() => { setIsMenuOpen(false); signOut(); }} className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm text-red-500 hover:bg-red-500/10 transition-colors cursor-pointer w-full">
                                    <LogOut className="w-4 h-4" />
                                    Sign Out
                                </button>
                            </div>
                        ) : (
                            <div className="pt-2 mt-2">
                                <button
                                    onClick={() => { setIsMenuOpen(false); signIn("discord"); }}
                                    data-umami-event="discord-sign-in-started"
                                    data-umami-event-location="navbar-mobile"
                                    className="flex items-center justify-center gap-2 w-full py-3 rounded-xl bg-orange-500 text-white font-bold text-sm hover:bg-orange-600 transition-colors cursor-pointer"
                                >
                                    <LogIn className="w-4 h-4" />
                                    Sign In with Discord
                                </button>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
