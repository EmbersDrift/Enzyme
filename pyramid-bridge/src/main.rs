fn main() {
    let args: Vec<_>=std::env::args().collect();
    if args.len()==2 && args[1]=="--capabilities" { println!("{}",pyramid_core::capabilities()); return; }
    if args.len()==4 && args[1]=="--plan" {
        let result=args[2].parse::<u32>().map_err(|e|e.to_string()).and_then(|from| args[3].parse::<u32>().map_err(|e|e.to_string()).and_then(|to|pyramid_core::plan(from,to)));
        match result {Ok(plan)=>println!("{plan}"),Err(e)=>{eprintln!("{e}");std::process::exit(1);}} return;
    }
    if args.len()!=3 { eprintln!("Usage: embers-pyramid <request.json> <result.json> | --capabilities"); std::process::exit(2); }
    let result=std::fs::read_to_string(&args[1]).map_err(|e|e.to_string())
        .and_then(|s|serde_json::from_str::<pyramid_core::Request>(&s).map_err(|e|e.to_string()));
    let report=match result {Ok(r)=>pyramid_core::convert(r),Err(e)=>pyramid_core::failure(e)};
    let code=if report.success {0} else if report.status=="cancelled" {3} else {1};
    if let Err(e)=std::fs::write(&args[2],serde_json::to_vec_pretty(&report).unwrap()) { eprintln!("Cannot write result: {e}"); std::process::exit(2); }
    std::process::exit(code);
}
