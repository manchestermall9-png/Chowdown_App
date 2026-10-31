#!/usr/bin/env python3
# =========================================================
# CHOW DOWN POS / KITCHEN SYSTEM - VERSION v8.01
# =========================================================

import json, os, threading, socket, urllib.request, datetime
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

PORT = 8080
WINSMS_KEY = "B9884ACB-743B-406C-8966-7CAD50C4858D"
DIR = os.path.dirname(os.path.abspath(__file__)) or "/storage/emulated/0/ChowDown_Test"
os.makedirs(DIR, exist_ok=True)
os.chdir(DIR)

F_ORD = os.path.join(DIR, "orders.json")
F_STK = os.path.join(DIR, "stock.json")
F_MEN = os.path.join(DIR, "menu.json")
F_CAT = os.path.join(DIR, "categories.json")
F_CNT = os.path.join(DIR, "counter.json")
F_PHN = os.path.join(DIR, "phone_numbers.txt")
F_SALES = os.path.join(DIR, "daily_sales.json")

orders = []
stock = {"chicken": 25, "chips": 15, "fish": 10, "tea": 30}
menu = [
    {"id": "chicken", "name": "1/4 Chicken & Chips", "cat": "CHICKEN", "price": 60},
    {"id": "chips", "name": "Large Chips", "cat": "SIDES", "price": 30},
    {"id": "fish", "name": "Fish & Chips", "cat": "DOGS", "price": 55},
    {"id": "tea", "name": "Bush Tea / Coffee", "cat": "DRINKS", "price": 15}
]
categories = ["CHICKEN", "SIDES", "DOGS", "DRINKS"]
counter = 1

try:
    if os.path.exists(F_ORD): orders = json.load(open(F_ORD))
    if os.path.exists(F_STK): stock = json.load(open(F_STK))
    if os.path.exists(F_MEN): menu = json.load(open(F_MEN))
    if os.path.exists(F_CAT): categories = json.load(open(F_CAT))
    if os.path.exists(F_CNT): counter = json.load(open(F_CNT)).get("counter", 1)
except: 
    pass

lock = threading.Lock()

def save(f, d): 
    try: 
        json.dump(d, open(f, 'w'))
    except: 
        pass

def archive_daily_sales():
    """Calculates daily totals, best/least selling items, and archives to daily_sales.json"""
    try:
        if not orders:
            return
        
        total_rand = 0
        item_counts = {}

        for o in orders:
            total_rand += o.get('total', 0)
            for it in o.get('items', []):
                name = it.get('name', it.get('id', 'Unknown'))
                qty = it.get('qty', 0)
                item_counts[name] = item_counts.get(name, 0) + qty

        best_item = "None"
        least_item = "None"
        if item_counts:
            sorted_items = sorted(item_counts.items(), key=lambda x: x[1], reverse=True)
            best_item = f"{sorted_items[0][0]} ({sorted_items[0][1]} sold)"
            least_item = f"{sorted_items[-1][0]} ({sorted_items[-1][1]} sold)"

        report = {
            "date": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
            "total_rand": total_rand,
            "total_orders_count": len(orders),
            "item_breakdown": item_counts,
            "best_seller": best_item,
            "least_seller": least_item
        }

        history = []
        if os.path.exists(F_SALES):
            try:
                history = json.load(open(F_SALES))
                if not isinstance(history, list): history = [history]
            except:
                history = []
        
        history.append(report)
        save(F_SALES, history)
        print(f"[DAILY SALES ARCHIVED] Total: R{total_rand} | Best: {best_item} | Least: {least_item}")
    except Exception as e:
        print(f"[ERROR ARCHIVING SALES]: {e}")

def clean_phone_number(phone_str):
    if not phone_str: return ""
    p = ''.join(filter(str.isdigit, str(phone_str)))
    if p.startswith('27') and len(p) == 11:
        p = '0' + p[2:]
    return p

def send_sms(phone, msg):
    if not phone: return
    p = ''.join(filter(str.isdigit, str(phone)))
    if p.startswith('0') and len(p) == 10: p = '27' + p[1:]
    def go():
        try:
            req = urllib.request.Request("https://api.winsms.co.za/api/rest/v1/sms/outgoing/send", 
                data=json.dumps({"message": str(msg), "recipients": [{"mobileNumber": p}]}).encode(), method='POST')
            req.add_header('AUTHORIZATION', WINSMS_KEY)
            req.add_header('Content-Type', 'application/json')
            req.add_header('Accept', 'application/json')
            res = urllib.request.urlopen(req, timeout=10).read().decode()
            print(f"[WINSMS SUCCESS] Sent to {p}. Response: {res}")
        except Exception as e: 
            print(f"[WINSMS ERROR] {e}")
    threading.Thread(target=go, daemon=True).start()

DISPLAY_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Chow Down - Customer Display Board</title>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.production.min.js"></script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.production.min.js"></script>
  <style>
    body{margin:0;font-family:sans-serif;background:#09090b;color:#fafafa;padding:15px}
    h1{color:#facc15;text-align:center;font-size:30px;margin-bottom:20px}
    .columns-grid{display:grid;grid-template-columns:repeat(3, 1fr);gap:15px;max-width:1400px;margin:0 auto}
    .col{border-radius:16px;padding:15px;min-height:70vh}
    .received-col{background:#ffffff;color:#000000;border:2px solid #e4e4e7}
    .preparing-col{background:#facc15;color:#000000;border:2px solid #eab308}
    .ready-col{background:#16a34a;color:#ffffff;border:2px solid #15803d}
    .col-title{font-size:22px;font-weight:bold;text-align:center;margin-bottom:15px;padding-bottom:8px;border-bottom:2px solid rgba(0,0,0,0.15);color:#000000 !important}
    .ready-col .col-title{border-bottom:2px solid rgba(255,255,255,0.3);color:#000000 !important}
    .orders-grid{display:grid;grid-template-columns:repeat(4, 1fr);gap:10px}
    .order-box{background:rgba(0,0,0,0.08);border-radius:10px;padding:12px 6px;text-align:center;font-size:22px;font-weight:bold;color:#000000}
    .ready-col .order-box{background:rgba(255,255,255,0.25);color:#ffffff}
    .empty-text{text-align:center;color:rgba(0,0,0,0.6);font-size:16px;margin-top:30px;grid-column: span 4;}
    .ready-col .empty-text{color:rgba(255,255,255,0.8);}
  </style>
</head>
<body>
  <div id="root"></div>
  <script>
    const { useState, useEffect } = React;
    const { createRoot } = ReactDOM;
    function DisplayApp() {
      const [orders, setOrders] = useState([]);
      useEffect(() => {
        const fetchOrders = async () => {
          try {
            const res = await fetch('/api/orders');
            if (res.ok) setOrders(await res.json());
          } catch(e) {}
        };
        fetchOrders();
        const interval = setInterval(fetchOrders, 2000);
        return () => clearInterval(interval);
      }, []);

      const activeOrders = orders.filter(o => o.status !== 'collected').sort((a,b) => a.num - b.num);
      
      const receivedOrders = activeOrders.filter(o => o.status === 'new' || !o.status);
      const preparingOrders = activeOrders.filter(o => o.status === 'preparing');
      const readyOrders = activeOrders.filter(o => o.status === 'complete');

      return React.createElement('div', null,
        React.createElement('h1', null, '📺 CHOW DOWN LIVE STATUS BOARD'),
        React.createElement('div', {className: 'columns-grid'},
          React.createElement('div', {className: 'col received-col'},
            React.createElement('div', {className: 'col-title'}, 'RECEIVED'),
            React.createElement('div', {className: 'orders-grid'},
              receivedOrders.length === 0 ? React.createElement('div', {className: 'empty-text'}, 'No orders') :
              receivedOrders.map(o => React.createElement('div', {key: o.num, className: 'order-box'}, '#' + o.num))
            )
          ),
          React.createElement('div', {className: 'col preparing-col'},
            React.createElement('div', {className: 'col-title'}, 'PREPARING'),
            React.createElement('div', {className: 'orders-grid'},
              preparingOrders.length === 0 ? React.createElement('div', {className: 'empty-text'}, 'No orders') :
              preparingOrders.map(o => React.createElement('div', {key: o.num, className: 'order-box'}, '#' + o.num))
            )
          ),
          React.createElement('div', {className: 'col ready-col'},
            React.createElement('div', {className: 'col-title'}, 'READY'),
            React.createElement('div', {className: 'orders-grid'},
              readyOrders.length === 0 ? React.createElement('div', {className: 'empty-text'}, 'No orders') :
              readyOrders.map(o => React.createElement('div', {key: o.num, className: 'order-box'}, '#' + o.num))
            )
          )
        )
      );
    }
    createRoot(document.getElementById('root')).render(React.createElement(DisplayApp));
  </script>
</body>
</html>"""

HTML_CONTENT = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Chow Down Trailer OS v8.01</title>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.production.min.js"></script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.production.min.js"></script>
  <style>
    body{margin:0;font-family:sans-serif;background:#09090b;color:#fafafa;padding-bottom:50px}
    .header{background:#18181b;border-bottom:2px solid #27272a;padding:14px;display:flex;justify-content:space-between;align-items:center;position:sticky;top:0;z-index:100}
    .btn{font-weight:700;padding:10px 16px;border-radius:10px;border:none;cursor:pointer}
    .btn-yellow{background:#facc15;color:#000}
    .btn-zinc{background:#27272a;color:#fafafa}
    .btn-green{background:#16a34a;color:#fff}
    .btn-blue{background:#2563eb;color:#fff}
    .btn-red{background:#dc2626;color:#fff}
    .card{background:#18181b;border:1px solid #27272a;border-radius:14px;padding:16px;margin-bottom:14px}
    .container{max-width:900px;margin:0 auto;padding:14px}
    .badge{padding:6px 12px;border-radius:8px;font-size:14px;font-weight:bold;background:#facc15;color:#000}
    input, select, textarea{background:#27272a;color:#fafafa;padding:10px;border-radius:8px;border:1px solid #3f3f46;width:100%;margin-bottom:10px;font-size:16px}
    .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
    @media(max-width: 768px) { .grid { grid-template-columns: 1fr; } }
    .receipt { background: #fef08a; color: #000; font-family: monospace; padding: 16px; border-radius: 8px; margin-bottom: 15px; }
    .columns-grid{display:grid;grid-template-columns:repeat(3, 1fr);gap:12px}
    .col{border-radius:12px;padding:12px;min-height:60vh}
    .received-col{background:#ffffff;color:#000000;border:1px solid #e4e4e7}
    .preparing-col{background:#facc15;color:#000000;border:1px solid #eab308}
    .ready-col{background:#16a34a;color:#ffffff;border:1px solid #15803d}
    .col-title{font-size:15px;font-weight:bold;text-align:center;margin-bottom:12px;padding-bottom:6px;border-bottom:1px solid rgba(0,0,0,0.15);color:#000000 !important}
    .ready-col .col-title{border-bottom:1px solid rgba(255,255,255,0.3);color:#000000 !important}
    .orders-grid{display:grid;grid-template-columns:repeat(4, 1fr);gap:8px}
    .order-box{background:rgba(0,0,0,0.08);border-radius:6px;padding:10px 4px;text-align:center;font-size:18px;font-weight:bold;color:#000000}
    .ready-col .order-box{background:rgba(255,255,255,0.25);color:#ffffff}
  </style>
</head>
<body>
  <div id="root"></div>
  <script>
    const { useState, useEffect, useMemo } = React;
    const { createRoot } = ReactDOM;

    function App() {
      const [role, setRole] = useState("ORDER");
      const [isServerTab, setIsServerTab] = useState(false);
      const [device, setDevice] = useState("PHONE");
      const [menu, setMenu] = useState([]);
      const [categories, setCategories] = useState(["CHICKEN", "SIDES", "DOGS", "DRINKS"]);
      const [stock, setStock] = useState({});
      const [orders, setOrders] = useState([]);
      const [cart, setCart] = useState({});
      const [customerPhone, setCustomerPhone] = useState("");
      const [currentCounter, setCurrentCounter] = useState(1);
      const [lastOrderNum, setLastOrderNum] = useState(null);
      const [isOnline, setIsOnline] = useState(true);

      const [newItemName, setNewItemName] = useState("");
      const [newItemCat, setNewItemCat] = useState("CHICKEN");
      const [newItemPrice, setNewItemPrice] = useState("");
      const [newItemStock, setNewItemStock] = useState("20");
      const [newCategoryName, setNewCategoryName] = useState("");

      const [phoneNumbers, setPhoneNumbers] = useState([]);
      const [selectedPhones, setSelectedPhones] = useState(new Set());
      const [bulkSmsMessage, setBulkSmsMessage] = useState("Hello Chow Down customer! Check out our latest specials today.");

      useEffect(() => {
        const hostCheck = window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1" || window.location.port === "8080";
        setIsServerTab(hostCheck);
        if (hostCheck) setDevice("TAB-8 SERVER");

        localStorage.setItem("chowdown_last_origin", window.location.origin);

        const fetchData = async () => {
          try {
            const res = await fetch('/api/orders'); 
            if (res.ok) {
              setOrders(await res.json());
              setIsOnline(true);
            } else {
              setIsOnline(false);
            }
            
            const sRes = await fetch('/api/stock'); if (sRes.ok) setStock(await sRes.json());
            const mRes = await fetch('/api/menu'); if (mRes.ok) setMenu(await mRes.json());
            const cRes = await fetch('/api/categories'); if (cRes.ok) setCategories(await cRes.json());
            
            const cntRes = await fetch('/api/counter');
            if (cntRes.ok) { 
              const cntData = await cntRes.json(); 
              setCurrentCounter(cntData.counter); 
            }

            const pRes = await fetch('/api/phones');
            if (pRes.ok) setPhoneNumbers(await pRes.json());
          } catch(e) {
            setIsOnline(false);
          }
        };

        fetchData();
        const interval = setInterval(fetchData, 2000);
        return () => clearInterval(interval);
      }, []);

      const handleReconnect = () => {
        const lastOrigin = localStorage.getItem("chowdown_last_origin") || window.location.origin;
        const targetUrl = prompt("Confirm or enter Tab-8 Server URL to reconnect (e.g. http://192.168.8.199:8080):", lastOrigin);
        if (targetUrl) {
          window.location.href = targetUrl.trim();
        }
      };

      const cartItems = useMemo(() => {
        return Object.entries(cart).filter(([id, qty]) => qty > 0).map(([id, qty]) => {
          const item = menu.find(m => m.id === id);
          return { id, name: item ? item.name : id, qty, price: item ? item.price : 0, ticked: false };
        });
      }, [cart, menu]);

      const totalAmount = cartItems.reduce((sum, item) => sum + (item.qty * item.price), 0);
      const activeOrderNumDisplay = lastOrderNum || currentCounter;

      const triggerPrint = (orderNum, itemsList, totalVal, phoneVal) => {
        const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        
        const ESC = '\\x1b';
        const bigBoldOn = ESC + '!' + '\\x38';      
        const twiceBoldOn = ESC + '!' + '\\x10';    
        const boldOn = ESC + 'E' + '\\x01';
        const resetSize = ESC + '!' + '\\x00';

        let text = `${bigBoldOn}      ORDER #${orderNum}\\n${resetSize}`;
        
        if (phoneVal && phoneVal.trim() !== '') {
          text += `${twiceBoldOn}    CELL: ${phoneVal.trim()}\\n${resetSize}`;
        }

        text += `${boldOn}================================\\n       CHOW DOWN MOBILE       \\n================================\\nTime: ${timeStr} | Device: ${device}\\n--------------------------------\\n${resetSize}`;
        
        itemsList.forEach(i => { 
          text += `${twiceBoldOn}${i.qty}x ${i.name}\\n   @ R${i.price} each = R${i.qty * i.price}\\n${resetSize}`; 
        });
        
        text += `${boldOn}--------------------------------\\n${resetSize}${bigBoldOn}TOTAL: R${totalVal}\\n${resetSize}${boldOn}================================\\n  Thank You For Eating At Chow Down \\nPlease collect your order at the trailer.\\n================================\\n\\n\\n${resetSize}`;
        
        const rawUrl = "rawbt:data:text/plain;base64," + btoa(text);
        
        window.location.href = rawUrl;
        
        setTimeout(() => {
          window.location.href = rawUrl;
        }, 5000);
      };

      const sendOrderToKitchen = async () => {
        if (cartItems.length === 0) return;
        try {
          const res = await fetch('/api/orders', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ items: cartItems, total: totalAmount, phone: customerPhone.trim() })
          });
          const jsonRes = await res.json();
          if (jsonRes.ok) {
            setLastOrderNum(jsonRes.num);
            setCurrentCounter(jsonRes.num + 1);
            const sRes = await fetch('/api/stock');
            if (sRes.ok) setStock(await sRes.json());
            setCart({});
            setCustomerPhone("");
            setIsOnline(true);
            alert(`Order #${jsonRes.num} sent to kitchen!`);
          } else {
            alert(jsonRes.error || 'Server error processing order');
          }
        } catch(e) {
          setIsOnline(false);
          alert('⚠️ Network Offline! Order failed to sync with Tab-8 server. Please check Wi-Fi connection.');
        }
      };

      const updateOrderStatus = async (e, orderNum, newStatus, updatedItems = null) => {
        if (e) e.preventDefault();
        try {
          const target = orders.find(o => o.num === orderNum);
          if (!target) return;
          if (newStatus === 'collected' && !window.confirm(`Clear Order #${orderNum}?`)) return;
          
          const isMarkingComplete = (newStatus === 'complete' && target.status !== 'complete');
          const updated = { ...target, status: newStatus };
          if (updatedItems) updated.items = updatedItems;

          const res = await fetch('/api/orders', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ...updated, trigger_sms: isMarkingComplete })
          });

          if (res.ok) setIsOnline(true);

          if (isMarkingComplete && target.phone && target.phone.trim() !== '') {
            alert(`[SMS SENT] Order #${orderNum} notification sent to ${target.phone}`);
          }

          const fetchRes = await fetch('/api/orders');
          if (fetchRes.ok) setOrders(await fetchRes.json());
        } catch(err) {
          setIsOnline(false);
        }
      };

      const toggleItemTick = (orderNum, itemIdx) => {
        const target = orders.find(o => o.num === orderNum);
        if (!target) return;
        const newItems = target.items.map((it, idx) => idx === itemIdx ? { ...it, ticked: !it.ticked } : it);
        updateOrderStatus(null, orderNum, target.status, newItems);
      };

      const updateStockCount = async (itemId, delta) => {
        const newStock = { ...stock, [itemId]: Math.max(0, (stock[itemId] || 0) + delta) };
        setStock(newStock);
        try {
          await fetch('/api/stock', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(newStock) });
        } catch(e) {}
      };

      const handleAddMenuItem = async (e) => {
        e.preventDefault();
        if (!newItemName || !newItemPrice) return alert("Enter item name and price");
        const id = newItemName.toLowerCase().replace(/\\s+/g, '_');
        const price = parseFloat(newItemPrice);
        const newMenu = [...menu, { id, name: newItemName, cat: newItemCat, price }];
        const newStock = { ...stock, [id]: parseInt(newItemStock) || 10 };
        try {
          await fetch('/api/menu', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(newMenu) });
          await fetch('/api/stock', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(newStock) });
          setMenu(newMenu); setStock(newStock);
          setNewItemName(""); setNewItemPrice("");
          alert("Menu item added!");
        } catch(e) { alert("Failed to add item."); }
      };

      const handleDeleteMenuItem = async (id) => {
        if (!window.confirm("Delete this menu item?")) return;
        const newMenu = menu.filter(m => m.id !== id);
        try {
          await fetch('/api/menu', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(newMenu) });
          setMenu(newMenu);
        } catch(e) {}
      };

      const handleAddCategory = async (e) => {
        e.preventDefault();
        if (!newCategoryName) return;
        const formatted = newCategoryName.toUpperCase().trim();
        if (categories.includes(formatted)) return alert("Category already exists");
        const newCats = [...categories, formatted];
        try {
          await fetch('/api/categories', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(newCats) });
          setCategories(newCats); setNewCategoryName("");
          alert("Category added!");
        } catch(e) { alert("Failed to add category."); }
      };

      const handleResetCounter = async () => {
        const val = prompt("Enter new starting order number:", currentCounter);
        if (val === null) return;
        const num = parseInt(val);
        if (isNaN(num)) return alert("Invalid number");
        try {
          await fetch('/api/counter', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ counter: num }) });
          setCurrentCounter(num); setLastOrderNum(null);
          alert("Order counter updated!");
        } catch(e) { alert("Failed to update counter."); }
      };

      const handleCashOutDay = async () => {
        if (!window.confirm("Cash out the day? This will archive sales and reset active orders.")) return;
        try {
          const res = await fetch('/api/counter', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ counter: currentCounter, reset_day: true }) });
          if(res.ok) {
            setOrders([]);
            alert("Day's sales successfully cashed out and archived!");
          }
        } catch(e) { alert("Failed to cash out."); }
      };

      const togglePhoneSelection = (phone) => {
        const newSelected = new Set(selectedPhones);
        if (newSelected.has(phone)) {
          newSelected.delete(phone);
        } else {
          newSelected.add(phone);
        }
        setSelectedPhones(newSelected);
      };

      const selectAllPhones = (e) => {
        if (e.target.checked) {
          setSelectedPhones(new Set(phoneNumbers));
        } else {
          setSelectedPhones(new Set());
        }
      };

      const sendBulkSms = async () => {
        if (selectedPhones.size === 0) return alert("Select at least one number.");
        if (!bulkSmsMessage.trim()) return alert("Enter an SMS message.");
        
        if (!window.confirm(`Send SMS to ${selectedPhones.size} contacts?`)) return;

        try {
          const res = await fetch('/api/bulk-sms', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ numbers: Array.from(selectedPhones), message: bulkSmsMessage })
          });
          const jsonRes = await res.json();
          if (jsonRes.ok) {
            alert(`[SUCCESS] SMS sent to ${jsonRes.sent_count} contact(s)!`);
            setSelectedPhones(new Set());
            setBulkSmsMessage("");
          } else {
            alert(jsonRes.error || 'Server error sending SMS');
          }
        } catch(e) {
          setIsOnline(false);
          alert('⚠️ Network Offline! Failed to send bulk SMS. Please check Wi-Fi connection.');
        }
      };

      const activeKitchenOrders = useMemo(() => orders.filter(o => o.status !== 'collected').sort((a, b) => a.num - b.num), [orders]);
      const activeSalesOrders = useMemo(() => orders.filter(o => o.status === 'complete' || o.status === 'collected'), [orders]);
      
      const totalRandSales = useMemo(() => activeSalesOrders.reduce((sum, o) => sum + o.total, 0), [activeSalesOrders]);
      const salesItemBreakdown = useMemo(() => {
        const counts = {};
        activeSalesOrders.forEach(o => {
          o.items.forEach(it => {
            const name = it.name || it.id;
            counts[name] = (counts[name] || 0) + it.qty;
          });
        });
        return counts;
      }, [activeSalesOrders]);
      
      const sortedSalesItems = useMemo(() => {
        return Object.entries(salesItemBreakdown).sort((a,b) => b[1] - a[1]);
      }, [salesItemBreakdown]);

      const receivedOrders = useMemo(() => activeKitchenOrders.filter(o => o.status === 'new' || !o.status), [activeKitchenOrders]);
      const preparingOrders = useMemo(() => activeKitchenOrders.filter(o => o.status === 'preparing'), [activeKitchenOrders]);
      const readyOrders = useMemo(() => activeKitchenOrders.filter(o => o.status === 'complete'), [activeKitchenOrders]);

      return React.createElement('div', null,
        React.createElement('div', { className: 'header' },
          React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
            React.createElement('h2', { style: { color: '#facc15', margin: 0 } }, 'CHOW DOWN'),
            React.createElement('span', { style: { background: '#27272a', padding: '2px 8px', borderRadius: 6, fontSize: 12, color: '#a1a1aa' } }, 'v8.01'),
            React.createElement('button', { 
              className: 'btn',
              onClick: handleReconnect,
              title: "Click to Reconnect / Reset Connection",
              style: { 
                background: isOnline ? '#064e3b' : '#7f1d1d', 
                color: isOnline ? '#6ee7b7' : '#fca5a5', 
                border: '1px solid ' + (isOnline ? '#10b981' : '#ef4444'),
                padding: '10px 16px', 
                borderRadius: 10, 
                fontSize: 14, 
                fontWeight: '700', 
                cursor: 'pointer' 
              } 
            }, isOnline ? '🟢 ONLINE' : '🔴 OFFLINE')
          ),
          React.createElement('div', { style: { display: 'flex', gap: 6 } },
            React.createElement('button', { className: 'btn ' + (role === 'ORDER' ? 'btn-yellow' : 'btn-zinc'), onClick: () => setRole('ORDER') }, 'ORDER'),
            React.createElement('button', { className: 'btn ' + (role === 'KITCHEN' ? 'btn-yellow' : 'btn-zinc'), onClick: () => setRole('KITCHEN') }, 'KITCHEN (' + activeKitchenOrders.length + ')'),
            isServerTab && React.createElement('button', { className: 'btn ' + (role === 'SALES' ? 'btn-yellow' : 'btn-zinc'), onClick: () => setRole('SALES') }, '📊 SALES'),
            isServerTab && React.createElement('button', { className: 'btn ' + (role === 'DISPLAY' ? 'btn-yellow' : 'btn-zinc'), onClick: () => setRole('DISPLAY') }, '📺 DISPLAY'),
            isServerTab && React.createElement('button', { className: 'btn ' + (role === 'SETUP' ? 'btn-yellow' : 'btn-zinc'), onClick: () => setRole('SETUP') }, '⚙️ SETUP'),
            React.createElement('button', { className: 'btn ' + (role === 'STOCK' ? 'btn-yellow' : 'btn-zinc'), onClick: () => setRole('STOCK') }, 'STOCK'),
            React.createElement('button', { className: 'btn ' + (role === 'NUMBERS' ? 'btn-yellow' : 'btn-zinc'), onClick: () => setRole('NUMBERS') }, '📱 NUMBERS')
          )
        ),
        React.createElement('div', { className: 'container' },
          role === 'ORDER' ? React.createElement('div', null,
            !isOnline && React.createElement('div', { className: 'card', style: { background: '#7f1d1d', border: '2px solid #ef4444', textAlign: 'center', marginBottom: 15 } },
              React.createElement('div', { style: { fontWeight: 'bold', fontSize: 16, color: '#fca5a5', marginBottom: 6 } }, '⚠️ WARNING: OFFLINE FROM TAB-8 SERVER'),
              React.createElement('div', { style: { fontSize: 13, marginBottom: 10 } }, 'Your phone has lost connection to the tablet server. Order numbers may get out of sync if you continue taking orders.'),
              React.createElement('button', { className: 'btn btn-yellow', onClick: handleReconnect }, '🔄 Reconnect to Last Active Connection')
            ),
            React.createElement('h3', { style: { color: '#facc15' } }, 'Menu & Ordering (Next: #' + currentCounter + ')'),
            menu.map(item => React.createElement('div', { key: item.id, className: 'card', style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' } },
              React.createElement('div', null,
                React.createElement('div', { style: { fontWeight: 'bold', fontSize: 18 } }, item.name, ' ', React.createElement('span', { style: { fontSize: 12, background: '#27272a', padding: '2px 6px', borderRadius: 4, color: '#a1a1aa' } }, item.cat)),
                React.createElement('div', { style: { color: '#facc15' } }, 'R', item.price, ' (Stock: ', stock[item.id] || 0, ')')
              ),
              React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 12 } },
                React.createElement('button', { className: 'btn btn-zinc', onClick: () => setCart(c => ({ ...c, [item.id]: Math.max(0, (c[item.id] || 0) - 1) })) }, '-'),
                React.createElement('span', { style: { fontSize: 18, fontWeight: 'bold', width: 20, textAlign: 'center' } }, cart[item.id] || 0),
                React.createElement('button', { className: 'btn btn-yellow', onClick: () => setCart(c => ({ ...c, [item.id]: (c[item.id] || 0) + 1 })) }, '+')
              )
            )),
            cartItems.length > 0 && React.createElement('div', { className: 'card', style: { border: '2px solid #facc15' } },
              React.createElement('h4', { style: { color: '#facc15', margin: '0 0 10px 0' } }, '📱 Customer Phone for SMS'),
              React.createElement('input', { type: 'tel', placeholder: 'e.g. 082 123 4567', value: customerPhone, onChange: e => setCustomerPhone(e.target.value) }),
              React.createElement('div', { className: 'receipt' },
                React.createElement('div', { style: { fontWeight: 'bold' } }, 'ORDER #' + activeOrderNumDisplay),
                customerPhone && React.createElement('div', null, 'Phone: ' + customerPhone),
                cartItems.map((ci, idx) => React.createElement('div', { key: idx }, ci.qty + 'x ' + ci.name + ' - R' + (ci.qty * ci.price))),
                React.createElement('div', { style: { fontWeight: 'bold', marginTop: 6 } }, 'TOTAL: R' + totalAmount)
              ),
              React.createElement('div', { style: { display: 'flex', gap: 10 } },
                React.createElement('button', { className: 'btn btn-zinc', style: { flex: 1, padding: 14 }, onClick: () => triggerPrint(activeOrderNumDisplay, cartItems, totalAmount, customerPhone) }, '🖨️ Print'),
                React.createElement('button', { className: 'btn btn-green', style: { flex: 1, padding: 14 }, onClick: sendOrderToKitchen }, '➡️ Send to Kitchen')
              )
            )
          ) : role === 'STOCK' ? React.createElement('div', null,
            React.createElement('h3', { style: { color: '#facc15' } }, 'Stock Management'),
            Object.entries(stock).map(([k, v]) => React.createElement('div', { key: k, className: 'card', style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' } },
              React.createElement('span', { style: { fontWeight: 'bold', textTransform: 'uppercase' } }, k),
              React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 12 } },
                React.createElement('span', { className: 'badge' }, v, ' left'),
                React.createElement('button', { className: 'btn btn-zinc', onClick: () => updateStockCount(k, -1) }, '-'),
                React.createElement('button', { className: 'btn btn-yellow', onClick: () => updateStockCount(k, 1) }, '+')
              )
            ))
          ) : role === 'SETUP' && isServerTab ? React.createElement('div', null,
            React.createElement('h3', { style: { color: '#facc15' } }, 'Setup & Administrative Controls'),
            React.createElement('div', { className: 'card' },
              React.createElement('h4', { style: { color: '#facc15', margin: '0 0 10px 0' } }, 'End-of-Day Cash Out'),
              React.createElement('button', { className: 'btn btn-red', style: { width: '100%', marginBottom: 10 }, onClick: handleCashOutDay }, '💰 Cash Out Day (Archive & Clear Orders)'),
              React.createElement('h4', { style: { color: '#facc15', margin: '15px 0 10px 0' } }, 'Order Counter Management'),
              React.createElement('p', { style: { color: '#a1a1aa', fontSize: 14 } }, 'Current Next Order #: ' + currentCounter),
              React.createElement('button', { className: 'btn btn-blue', onClick: handleResetCounter }, '⚙️ Reset / Change Order Counter')
            ),
            React.createElement('div', { className: 'card' },
              React.createElement('h4', { style: { color: '#facc15', margin: '0 0 10px 0' } }, 'Manage Categories'),
              React.createElement('form', { onSubmit: handleAddCategory },
                React.createElement('input', { type: 'text', placeholder: 'New Category Name', value: newCategoryName, onChange: e => setNewCategoryName(e.target.value) }),
                React.createElement('button', { className: 'btn btn-green', type: 'submit', style: { width: '100%' } }, 'Add Category')
              ),
              React.createElement('div', { style: { marginTop: 10, display: 'flex', gap: 6, flexWrap: 'wrap' } },
                categories.map(c => React.createElement('span', { key: c, style: { background: '#27272a', padding: '4px 8px', borderRadius: 6, fontSize: 13 } }, c))
              )
            ),
            React.createElement('div', { className: 'card' },
              React.createElement('h4', { style: { color: '#facc15', margin: '0 0 10px 0' } }, 'Add Menu Item'),
              React.createElement('form', { onSubmit: handleAddMenuItem },
                React.createElement('input', { type: 'text', placeholder: 'Item Name', value: newItemName, onChange: e => setNewItemName(e.target.value) }),
                React.createElement('select', { value: newItemCat, onChange: e => setNewItemCat(e.target.value) }, categories.map(c => React.createElement('option', { key: c, value: c }, c))),
                React.createElement('input', { type: 'number', placeholder: 'Price (R)', value: newItemPrice, onChange: e => setNewItemPrice(e.target.value) }),
                React.createElement('button', { className: 'btn btn-green', type: 'submit', style: { width: '100%' } }, 'Add Item')
              )
            ),
            React.createElement('div', { className: 'card' },
              React.createElement('h4', { style: { color: '#facc15', margin: '0 0 10px 0' } }, 'Manage Menu Items'),
              menu.map(m => React.createElement('div', { key: m.id, style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 0', borderBottom: '1px solid #27272a' } },
                React.createElement('span', null, m.name, ' (R', m.price, ')'),
                React.createElement('button', { className: 'btn btn-red', style: { padding: '4px 8px', fontSize: 12 }, onClick: () => handleDeleteMenuItem(m.id) }, 'Delete')
              ))
            )
          ) : role === 'SALES' && isServerTab ? React.createElement('div', null,
            React.createElement('h3', { style: { color: '#facc15' } }, 'Live Sales Report'),
            React.createElement('div', { className: 'card', style: { background: '#047857', border: 'none' } },
              React.createElement('div', { style: { color: '#a7f3d0', fontSize: 14 } }, 'TOTAL SALES'),
              React.createElement('div', { style: { color: '#ffffff', fontSize: 40, fontWeight: 'bold' } }, 'R', totalRandSales)
            ),
            React.createElement('div', { className: 'grid', style: { gridTemplateColumns: '1fr 1fr' } },
              sortedSalesItems.length > 0 && React.createElement('div', { className: 'card', style: { borderColor: '#10b981' } },
                React.createElement('h4', { style: { color: '#10b981', margin: '0 0 10px 0' } }, '🏆 Best Sellers'),
                sortedSalesItems.map(([name, qty], idx) => React.createElement('div', { key: idx, style: { display: 'flex', justifyContent: 'space-between', padding: '6px 0' } },
                  React.createElement('span', { style: { fontWeight: 'bold' } }, name),
                  React.createElement('span', { className: 'badge' }, qty, ' sold')
                ))
              ),
              sortedSalesItems.length > 0 && React.createElement('div', { className: 'card', style: { borderColor: '#ef4444' } },
                React.createElement('h4', { style: { color: '#ef4444', margin: '0 0 10px 0' } }, '📉 Least Sellers'),
                [...sortedSalesItems].reverse().map(([name, qty], idx) => React.createElement('div', { key: idx, style: { display: 'flex', justifyContent: 'space-between', padding: '6px 0' } },
                  React.createElement('span', { style: { fontWeight: 'bold' } }, name),
                  React.createElement('span', { style: { backgroundColor: '#7f1d1d', color: '#fca5a5', padding: '6px 12px', borderRadius: 8, fontSize: 14, fontWeight: 'bold' } }, qty, ' sold')
                ))
              )
            ),
            React.createElement('div', { className: 'card' },
              React.createElement('h4', { style: { color: '#facc15', margin: '0 0 10px 0' } }, 'All Items Sold Breakdown'),
              sortedSalesItems.map(([name, qty], idx) => React.createElement('div', { key: idx, style: { display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid #27272a' } },
                React.createElement('span', null, name),
                React.createElement('span', { style: { fontWeight: 'bold' } }, qty, ' sold')
              ))
            )
          ) : role === 'DISPLAY' && isServerTab ? React.createElement('div', null,
            React.createElement('h3', { style: { color: '#facc15', marginBottom: 15 } }, 'Live Customer Status Board (3-Column View)'),
            React.createElement('div', { style: { marginBottom: 10 } },
              React.createElement('a', { href: '/display', target: '_blank', style: { color: '#facc15', fontWeight: 'bold', textDecoration: 'underline' } }, '🔗 Open Dedicated Full-Screen Display URL (/display)')
            ),
            React.createElement('div', { className: 'columns-grid' },
              React.createElement('div', { className: 'col received-col' },
                React.createElement('div', { className: 'col-title' }, 'RECEIVED'),
                React.createElement('div', { className: 'orders-grid' },
                  receivedOrders.length === 0 ? React.createElement('div', { style: { textAlign: 'center', color: 'rgba(0,0,0,0.6)', fontSize: 14, marginTop: 20, gridColumn: 'span 4' } }, 'No orders') :
                  receivedOrders.map(o => React.createElement('div', { key: o.num, className: 'order-box' }, '#' + o.num))
                )
              ),
              React.createElement('div', { className: 'col preparing-col' },
                React.createElement('div', { className: 'col-title' }, 'PREPARING'),
                React.createElement('div', { className: 'orders-grid' },
                  preparingOrders.length === 0 ? React.createElement('div', { style: { textAlign: 'center', color: 'rgba(0,0,0,0.6)', fontSize: 14, marginTop: 20, gridColumn: 'span 4' } }, 'No orders') :
                  preparingOrders.map(o => React.createElement('div', { key: o.num, className: 'order-box' }, '#' + o.num))
                )
              ),
              React.createElement('div', { className: 'col ready-col' },
                React.createElement('div', { className: 'col-title' }, 'READY'),
                React.createElement('div', { className: 'orders-grid' },
                  readyOrders.length === 0 ? React.createElement('div', { style: { textAlign: 'center', color: 'rgba(0,0,0,0.6)', fontSize: 14, marginTop: 20, gridColumn: 'span 4' } }, 'No orders') :
                  readyOrders.map(o => React.createElement('div', { key: o.num, className: 'order-box' }, '#' + o.num))
                )
              )
            )
          ) : role === 'NUMBERS' ? React.createElement('div', null,
            React.createElement('h3', { style: { color: '#facc15' } }, 'Customer Contact List (Bulk SMS)'),
            React.createElement('div', { className: 'card' },
              React.createElement('h4', { style: { color: '#facc15', margin: '0 0 10px 0' } }, 'Compose Bulk SMS'),
              React.createElement('textarea', { rows: 4, placeholder: 'Enter your custom SMS message here...', value: bulkSmsMessage, onChange: e => setBulkSmsMessage(e.target.value) }),
              React.createElement('button', { className: 'btn btn-green', style: { width: '100%', marginTop: 10 }, onClick: sendBulkSms }, 'Send Bulk SMS')
            ),
            React.createElement('div', { className: 'card' },
              React.createElement('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 } },
                React.createElement('h4', { style: { color: '#facc15', margin: 0 } }, 'Select Contacts (' + selectedPhones.size + ' selected)'),
                React.createElement('label', { style: { display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' } },
                  React.createElement('input', { type: 'checkbox', checked: selectedPhones.size === phoneNumbers.length && phoneNumbers.length > 0, onChange: selectAllPhones }),
                  'Select All'
                )
              ),
              phoneNumbers.map(phone => React.createElement('div', { key: phone, style: { display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0', borderBottom: '1px solid #27272a' } },
                React.createElement('input', { type: 'checkbox', checked: selectedPhones.has(phone), onChange: () => togglePhoneSelection(phone) }),
                React.createElement('span', null, phone)
              ))
            )
          ) : React.createElement('div', null,
            React.createElement('h3', { style: { color: '#facc15' } }, 'Kitchen Live Orders'),
            activeKitchenOrders.length === 0 ? React.createElement('div', { className: 'card', style: { textAlign: 'center', color: '#71717a' } }, 'No active orders.') : React.createElement('div', { className: 'grid' },
              activeKitchenOrders.map(o => React.createElement('div', { key: o.num, className: 'card', style: { borderLeft: '6px solid ' + (o.status === 'complete' ? '#16a34a' : '#facc15') } },
                React.createElement('div', { style: { display: 'flex', justifyContent: 'space-between', fontWeight: 'bold', marginBottom: 6, alignItems: 'center' } },
                  React.createElement('span', { style: { color: '#facc15', fontSize: 40 } }, 'ORDER #' + o.num),
                  React.createElement('span', { className: 'badge' }, (o.status || 'new').toUpperCase())
                ),
                o.phone && React.createElement('div', { style: { fontSize: 40, fontWeight: 'bold', color: '#4ade80', marginBottom: 10 } }, '📱 ' + o.phone),
                o.items.map((it, idx) => React.createElement('div', { key: idx, onClick: () => toggleItemTick(o.num, idx), style: { padding: '10px 12px', marginBottom: 8, borderRadius: 6, background: it.ticked ? '#064e3b' : '#27272a', color: it.ticked ? '#6ee7b7' : '#fafafa', cursor: 'pointer', fontWeight: 'bold', fontSize: 24, display: 'flex', alignItems: 'center', gap: 12 } },
                  React.createElement('input', { type: 'checkbox', checked: !!it.ticked, readOnly: true, style: { width: 22, height: 22, cursor: 'pointer', accentColor: '#16a34a' } }),
                  React.createElement('span', null, it.qty + 'x ' + it.name)
                )),
                React.createElement('div', { style: { display: 'flex', justifyContent: 'space-between', marginTop: 12, alignItems: 'center', paddingTop: 8, borderTop: '1px solid #27272a' } },
                  React.createElement('span', { style: {fontWeight: 'bold', color: '#facc15' } }, 'R', o.total),
                  React.createElement('div', { style: { gap: 6 } },
                    o.status !== 'preparing' && o.status !== 'complete' && React.createElement('button', { className: 'btn btn-yellow', onClick: (e) => updateOrderStatus(e, o.num, 'preparing') }, 'Prep'),
                    o.status !== 'complete' && React.createElement('button', { className: 'btn btn-green', onClick: (e) => updateOrderStatus(e, o.num, 'complete') }, 'Done'),
                    React.createElement('button', { className: 'btn btn-blue', onClick: (e) => updateOrderStatus(e, o.num, 'collected') }, 'Collect')
                  )
                )
              ))
            )
          )
        )
      );
    }
    createRoot(document.getElementById('root')).render(React.createElement(App));
  </script>
</body>
</html>
"""

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/display':
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(DISPLAY_HTML.encode('utf-8'))
            return
            
        if path in ['/', '/app', '/order', '/kitchen', '/stock', '/setup', '/app.html']:
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(HTML_CONTENT.encode('utf-8'))
            return

        if path == '/api/phones':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            unique_phones = []
            if os.path.exists(F_PHN):
                with open(F_PHN, 'r') as f:
                    lines = f.readlines()
                    cleaned_list = []
                    for line in lines:
                        cleaned = clean_phone_number(line.strip())
                        if cleaned:
                            cleaned_list.append(cleaned)
                    unique_phones = sorted(list(set(cleaned_list)))
            self.wfile.write(json.dumps(unique_phones).encode())
            return
            
        api = {
            '/api/orders': orders, 
            '/api/stock': stock, 
            '/api/menu': menu, 
            '/api/categories': categories, 
            '/api/counter': {"counter": counter}
        }
        if path in api:
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            with lock: 
                self.wfile.write(json.dumps(api[path]).encode())
            return
            
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        global orders, counter, stock, menu, categories
        p = urlparse(self.path).path
        cl = int(self.headers.get('Content-Length', 0))
        try:
            data = json.loads(self.rfile.read(cl).decode()) if cl > 0 else {}
            with lock:
                if p == '/api/counter':
                    if data.get('reset_day'):
                        archive_daily_sales()
                        orders = []
                        save(F_ORD, orders)

                    counter = int(data.get('counter', counter))
                    save(F_CNT, {"counter": counter})
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    self.wfile.write(json.dumps({"ok": True, "counter": counter}).encode())
                    return

                if p == '/api/orders':
                    if not data.get('num'):
                        data['num'] = counter
                        counter += 1
                        save(F_CNT, {"counter": counter})
                        data['status'] = 'new'
                        
                        items = data.get('items', [])
                        for item in items:
                            item_id = item.get('id')
                            qty = item.get('qty', 0)
                            if item_id in stock:
                                stock[item_id] = max(0, stock[item_id] - qty)
                        save(F_STK, stock)

                    phone_number = clean_phone_number(data.get('phone', ''))
                    if phone_number:
                        try:
                            existing = []
                            if os.path.exists(F_PHN):
                                with open(F_PHN, 'r') as f:
                                    existing = [clean_phone_number(line.strip()) for line in f.readlines()]
                            if phone_number not in existing:
                                with open(F_PHN, "a") as ph_file:
                                    ph_file.write(phone_number + "\n")
                        except Exception as e:
                            print(f"[ERROR] Could not save phone number: {e}")

                    if data.get('trigger_sms'):
                        order_num = data.get('num')
                        sms_msg = f"Hi! Your Chow Down order #{order_num} is READY for collection. Please pick it up at the trailer. Thank you!"
                        send_sms(data.get('phone', ''), sms_msg)
                        data.pop('trigger_sms', None)
                    
                    target_num = data.get('num')
                    new_orders = [o for o in orders if o.get('num') != target_num]
                    orders = new_orders
                    
                    orders.append(data)
                    save(F_ORD, orders)
                    
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    self.wfile.write(json.dumps({"ok": True, "num": data['num']}).encode())
                    return
                elif p == '/api/stock': 
                    stock = data
                    save(F_STK, stock)
                elif p == '/api/menu': 
                    menu = data
                    save(F_MEN, menu)
                elif p == '/api/categories': 
                    categories = data
                    save(F_CAT, categories)
                elif p == '/api/bulk-sms':
                    numbers = data.get('numbers', [])
                    msg = data.get('message', '')
                    sent_count = 0
                    for num in numbers:
                        send_sms(num, msg)
                        sent_count += 1
                        
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    self.wfile.write(json.dumps({"ok": True, "sent_count": sent_count}).encode())
                    return
                    
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"ok": True}).encode())
        except Exception as e:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(str(e).encode())

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def log_message(self, *a): pass

if __name__ == '__main__':
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
    except: 
        ip = "192.168.8.199"
    print(f"=== CHOW DOWN SERVER v8.01 ===")
    print(f"Server running at: http://{ip}:{PORT}/")
    print(f"Dedicated Display link: http://{ip}:{PORT}/display")
    ThreadingHTTPServer(('0.0.0.0', PORT), Handler).serve_forever()